<!--
  文件作用：前端页面视图文件，组织售后争议对应页面的数据加载、交互和展示。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

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
import {
  extractAgentRunDescriptor,
  loadActiveAgentRuns,
  resultRoomMessage,
} from "../../api/agentStream";
import { disputeApi } from "../../api/disputes";
import { hearingApi } from "../../api/hearing";
import { evidenceApi } from "../../api/evidence";
import { roomApi } from "../../api/rooms";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import AgentSpeakerLabel from "../../components/room/AgentSpeakerLabel.vue";
import AgentStreamErrorDialog from "../../components/room/AgentStreamErrorDialog.vue";
import PhaseCountdown from "../../components/room/PhaseCountdown.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
  createRoomState,
  streamRoomEvents,
} from "../../stores/room";
import {
  activeAgentStreams,
  clearAgentStreams,
  consumeAgentRun,
  durableMessagesOutsideActiveStreams,
  streamCardsForRun,
  visibleAgentStreams,
} from "../../stores/agentStream";
import { displayRoomMessageText } from "../../utils/displayText";
import {
  hearingFlowProgress,
  hearingFlowStage,
  hearingFlowStageDefinition,
  isPartyInputStage,
} from "../../utils/hearingFlow";

const props = defineProps({
  initialHearing: { type: Object, default: null },
  initialEvidenceCatalog: { type: Object, default: null },
  viewerRole: { type: String, default: "" },
  deadlineAt: { type: String, default: "" },
  serverNow: { type: String, default: "" },
  confirmSettlementAction: { type: Function, default: null },
  eventStreamer: { type: Function, default: null },
  initialEvents: { type: Array, default: null },
  initialMessages: { type: Array, default: null },
  messageAction: { type: Function, default: null },
  proposeSettlementAction: { type: Function, default: null },
  supplementAction: { type: Function, default: null },
  submitEvidenceBatchAction: { type: Function, default: null },
  submitAnswersAction: { type: Function, default: null },
  completeHearingAction: { type: Function, default: null },
});

const route = useRoute();
const router = useRouter();
const hearing = ref(props.initialHearing);
const evidenceCatalog = ref(props.initialEvidenceCatalog);
const hearingDeadlineAt = ref(props.deadlineAt);
const agentState = ref("LISTENING");
const reviewGateOpen = ref(false);
const error = ref("");
const streamError = ref("");
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
const evidenceDrawerSide = ref(null);
const expandedTranscriptIds = ref([]);
const hearingCourtroomPage = ref(null);
const courtTranscriptRail = ref(null);
const leftEvidenceDrawer = ref(null);
const rightEvidenceDrawer = ref(null);
const leftEvidenceDrawerTrigger = ref(null);
const rightEvidenceDrawerTrigger = ref(null);
const leftEvidenceDrawerClose = ref(null);
const rightEvidenceDrawerClose = ref(null);
const courtLedgerTrigger = ref(null);
const courtLedgerDrawer = ref(null);
const courtLedgerCloseButton = ref(null);
const proposalText = ref("");
const statementText = ref("");
const proposing = ref(false);
const supplementing = ref(false);
const checkingDraftStatus = ref(false);
const draftGenerationNoticeOpen = ref(false);
const draftEntryButton = ref(null);
const draftGenerationNoticeDialog = ref(null);
const draftGenerationNoticeClose = ref(null);
const pendingSupplementFiles = ref([]);
const pendingSupplementInput = ref(null);
const supplementDeclarationError = ref("");
const supplementDeclarationForm = reactive({
  claimedFact: "",
  truthAttested: false,
});
const submittingAnswers = ref(false);
const eventState = reactive(createRoomState());
const eventAbortController = new AbortController();
const EVIDENCE_DRAWER_BREAKPOINT = 1220;
const LONG_TRANSCRIPT_THRESHOLD = 1500;
const LONG_TRANSCRIPT_PREVIEW_LENGTH = 900;
const DRAFT_STATUS_RECHECK_DELAYS_MS = [300, 900, 1800];
const DRAFT_STATUS_RECHECK_STAGES = new Set([
  "JUDGE_V2_GENERATING",
  "HUMAN_REVIEW_OPEN",
  "CLOSED",
]);
let evidenceDrawerResizeObserver = null;
let evidenceDrawerWindowResizeHandler = null;
let courtLedgerReturnFocus = null;
let draftStatusSyncPromise = null;
let draftStatusRetryTimer = null;
let draftStatusRetryResolve = null;
let draftStatusSyncEnabled = true;
const mountedCaseId = String(route.params.caseId || "");
const caseId = computed(() => String(route.params.caseId || mountedCaseId));
const historyMode = computed(() => route.query.view === "history");
const shouldDiscoverActiveHearingRuns = computed(() =>
  !historyMode.value &&
  props.initialMessages === null &&
  props.initialHearing === null &&
  props.initialEvidenceCatalog === null &&
  !props.eventStreamer,
);
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
const hearingStreamingRuns = computed(() =>
  historyMode.value
    ? []
    : activeAgentStreams({
    caseId: caseId.value,
    roomType: "HEARING",
    actorId: effectiveActor.value.id,
    actorRole: effectiveActor.value.role,
  }),
);
const hearingTranscriptMessages = computed(() =>
  durableMessagesOutsideActiveStreams(
    messages.value,
    hearingStreamingRuns.value,
  ),
);
const visibleHearingStreamingRuns = computed(() =>
  visibleAgentStreams(
    hearingStreamingRuns.value,
    hearingTranscriptMessages.value,
  ).map((run) => {
    const cards = Object.fromEntries(
      Object.entries(run.cards || {}).map(([key, card]) => [
        key,
        { ...card, content: sanitizeHearingCopy(card.content) },
      ]),
    );
    return {
      ...run,
      cards,
      content: sanitizeHearingCopy(run.content),
    };
  }),
);
const isReviewer = computed(() => role.value === "PLATFORM_REVIEWER");
const settlements = computed(() => hearing.value?.settlements || []);
const hearingStatus = computed(() => hearing.value?.status || {});
const flowStageCode = computed(() => hearingFlowStage(hearingStatus.value));
const flowStageMeta = computed(() => hearingFlowStageDefinition(flowStageCode.value));
const questionSet = computed(
  () => hearing.value?.question_set || hearing.value?.questionSet || null,
);
const issueSet = computed(
  () => hearing.value?.issue_set || hearing.value?.issueSet || questionSet.value,
);
const activeIssueSetId = computed(
  () =>
    issueSet.value?.issue_set_id ||
    issueSet.value?.issueSetId ||
    issueSet.value?.question_set_id ||
    issueSet.value?.questionSetId ||
    "",
);

function targetValues(value, snakeKey, camelKey, singularSnakeKey, singularCamelKey) {
  const plural = value?.[snakeKey] || value?.[camelKey];
  if (Array.isArray(plural)) return plural.map(String);
  const singular = value?.[singularSnakeKey] || value?.[singularCamelKey];
  return singular ? [String(singular)] : [];
}

function promptTargetsCurrentActor(prompt) {
  const participantIds = targetValues(
    prompt,
    "target_participant_ids",
    "targetParticipantIds",
    "target_participant_id",
    "targetParticipantId",
  );
  if (participantIds.length) return participantIds.includes(String(effectiveActor.value.id));
  const roles = targetValues(
    prompt,
    "target_roles",
    "targetRoles",
    "target_role",
    "targetRole",
  );
  const roleSnapshot = prompt?.role_snapshot || prompt?.roleSnapshot;
  if (roleSnapshot) roles.push(String(roleSnapshot));
  const targetParty = prompt?.target_party || prompt?.targetParty;
  if (targetParty) roles.push(String(targetParty));
  return !roles.length || roles.includes(role.value);
}

function guidancePromptText(prompt) {
  if (typeof prompt === "string") return prompt;
  return (
    prompt?.prompt_text ||
    prompt?.promptText ||
    prompt?.question_text ||
    prompt?.questionText ||
    ""
  );
}

function currentActorIssuePrompt(value) {
  const rawPrompts = value?.party_prompts || value?.partyPrompts || value?.prompts || [];
  if (Array.isArray(rawPrompts)) {
    return guidancePromptText(rawPrompts.find(promptTargetsCurrentActor));
  }
  if (!rawPrompts || typeof rawPrompts !== "object") return "";
  return guidancePromptText(
    rawPrompts[String(effectiveActor.value.id)] ||
      rawPrompts[role.value] ||
      rawPrompts[role.value.toLowerCase()],
  );
}

const issueGuidanceItems = computed(() => {
  const explicitIssues = issueSet.value?.issues || [];
  if (explicitIssues.length) {
    return explicitIssues.map((issue, index) => {
      return {
        id: issue.issue_id || issue.issueId || `issue-${index + 1}`,
        statement:
          issue.issue_statement ||
          issue.issueStatement ||
          issue.dispute_point ||
          issue.disputePoint ||
          `争议点 ${index + 1}`,
        prompt: currentActorIssuePrompt(issue),
        factCount: (issue.fact_ids || issue.factIds || []).length,
      };
    });
  }

  const groups = new Map();
  for (const [index, question] of (issueSet.value?.questions || []).entries()) {
    const issueId =
      question.issue_id ||
      question.issueId ||
      question.question_id ||
      question.questionId ||
      `issue-${index + 1}`;
    const sharedStatement =
      question.issue_statement ||
      question.issueStatement ||
      question.dispute_point ||
      question.disputePoint ||
      "";
    const targetsCurrentActor = promptTargetsCurrentActor(question);
    if (!sharedStatement && !targetsCurrentActor) continue;
    const existing = groups.get(issueId) || {
      id: issueId,
      statement: sharedStatement,
      prompt: "",
      factCount: (question.fact_ids || question.factIds || []).length,
    };
    if (targetsCurrentActor) {
      const questionText = guidancePromptText(question);
      if (existing.statement) {
        existing.prompt = currentActorIssuePrompt(question) || questionText;
      } else {
        existing.statement = questionText;
        existing.prompt = currentActorIssuePrompt(question);
      }
    }
    groups.set(issueId, existing);
  }
  return [...groups.values()];
});
const evidenceRequestSet = computed(
  () => hearing.value?.evidence_request_set || hearing.value?.evidenceRequestSet || null,
);
const applicableEvidenceRequests = computed(() =>
  (evidenceRequestSet.value?.requests || []).filter((request) => {
    const targets =
      request.target_roles ||
      request.targetRoles ||
      (request.target_party || request.targetParty
        ? [request.target_party || request.targetParty]
        : []);
    return !targets.length || targets.includes(role.value);
  }),
);
const stageParticipantStatuses = computed(
  () =>
    hearingStatus.value?.participant_statuses ||
    hearingStatus.value?.participantStatuses ||
    [],
);
const legacyStagePartyStatuses = computed(
  () => hearingStatus.value?.party_statuses || hearingStatus.value?.partyStatuses || {},
);

function partyStatusValue(statuses, participantId, participantRole) {
  const id = String(participantId || "");
  const roleSnapshot = String(participantRole || "");
  const values = Array.isArray(statuses)
    ? statuses
    : statuses && typeof statuses === "object"
      ? Object.values(statuses)
      : [];
  if (
    id &&
    !Array.isArray(statuses) &&
    statuses &&
    typeof statuses === "object" &&
    Object.prototype.hasOwnProperty.call(statuses, id)
  ) {
    return statuses[id];
  }
  const participantMatch = id
    ? values.find(
        (item) =>
          item &&
          typeof item === "object" &&
          String(item.participant_id || item.participantId || "") === id,
      )
    : undefined;
  if (participantMatch !== undefined) return participantMatch;
  return (
    (Array.isArray(statuses) ? undefined : statuses?.[roleSnapshot]) ||
    values.find(
      (item) =>
        item &&
        typeof item === "object" &&
        String(item.participant_role || item.participantRole || "") === roleSnapshot,
    )
  );
}

function submissionStatus(value) {
  return String(
    value?.submission_status ||
      value?.submissionStatus ||
      value?.status ||
      value ||
      "PENDING",
  ).toUpperCase();
}

function stageStatusValue(participantId, participantRole) {
  const participantValue = partyStatusValue(
    stageParticipantStatuses.value,
    participantId,
    "",
  );
  return participantValue !== undefined
    ? participantValue
    : partyStatusValue(
        legacyStagePartyStatuses.value,
        participantId,
        participantRole,
      );
}

function optimisticParticipantStatuses(statuses, actorSnapshot, status) {
  const participantId = String(actorSnapshot.id);
  const nextStatuses = Array.isArray(statuses)
    ? [...statuses]
    : statuses && typeof statuses === "object"
      ? Object.values(statuses).filter((item) => item && typeof item === "object")
      : [];
  const index = nextStatuses.findIndex(
    (item) =>
      String(item?.participant_id || item?.participantId || "") === participantId,
  );
  const nextStatus = {
    ...(index >= 0 && typeof nextStatuses[index] === "object"
      ? nextStatuses[index]
      : {}),
    participant_id: participantId,
    participant_role: actorSnapshot.role,
    status,
    submission_status: status,
  };
  if (index >= 0) nextStatuses[index] = nextStatus;
  else nextStatuses.push(nextStatus);
  return nextStatuses;
}

const currentActorStageStatus = computed(() => {
  return submissionStatus(
    stageStatusValue(effectiveActor.value.id, role.value),
  );
});
const currentActorStageTerminal = computed(() =>
  ["SUBMITTED", "AUTO_TIMEOUT"].includes(currentActorStageStatus.value),
);
const activeSettlement = computed(
  () =>
    settlements.value.find((settlement) => settlement.status !== "SUPERSEDED") ||
    null,
);
const effectiveDeadline = computed(
  () => props.deadlineAt || hearingDeadlineAt.value || "",
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
  if (hearingStreamingRuns.value.length > 0) return "connected";
  if (eventState.connected) return "connected";
  if (eventState.reconnecting) return "reconnecting";
  return "offline";
});
const evidenceSourceType = computed(() => {
  if (role.value === "MERCHANT") return "MERCHANT_UPLOAD";
  if (role.value === "USER") return "USER_UPLOAD";
  return "PLATFORM_UPLOAD";
});
const partyRoles = ["USER", "MERCHANT"];
const reviewGateEvents = new Set([
  "HEARING_FLOW_STAGE_CHANGED",
  "REVIEW_TASK_CREATED",
  "JUDGE_V2_READY",
]);
const HEARING_FLOW_AGENT_OPERATIONS = new Set([
  "HEARING_INTAKE_QUESTIONS",
  "HEARING_INTAKE_SYNTHESIS",
  "HEARING_EVIDENCE_REQUESTS",
  "HEARING_EVIDENCE_SYNTHESIS",
  "HEARING_JUDGE_V1",
  "HEARING_JURY_REVIEW",
  "HEARING_JUDGE_V2",
]);
const isCaseParty = computed(() => partyRoles.includes(role.value));
const submittedRoles = computed(() =>
  partyRoles.filter((partyRole) => {
    const participantId =
      partyRole === role.value
        ? effectiveActor.value.id
        : demoActorIds[partyRole];
    const status = submissionStatus(stageStatusValue(participantId, partyRole));
    return ["SUBMITTED", "AUTO_TIMEOUT"].includes(status);
  }),
);
const allPartiesStageTerminal = computed(() =>
  partyRoles.every((partyRole) => submittedRoles.value.includes(partyRole)),
);
const currentActorSubmitted = computed(() => currentActorStageTerminal.value);

// 业务位置：【前端庭审】messageSenderRole：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function messageSenderRole(message) {
  return message?.sender_role || message?.senderRole || "";
}

// 业务位置：【前端庭审】messageType：读取 房间消息和对话记录，并依据当前案件、角色和会话权限裁剪成可用输入。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function messageType(message) {
  return String(message?.message_type || message?.messageType || "").toUpperCase();
}

const reviewStageReached = computed(
  () =>
    ["HUMAN_REVIEW_OPEN", "CLOSED"].includes(flowStageCode.value),
);
const reviewHandoffVisible = computed(
  () => isCaseParty.value && reviewStageReached.value,
);
const latestDraftId = computed(() =>
  statusField("latest_draft_id", "latestDraftId", ""),
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
const serverReviewGateReady = computed(() =>
  Boolean(statusField("review_gate_ready", "reviewGateReady", false)),
);
const draftReadyForResult = computed(
  () =>
    serverCanCompleteHearing.value ||
    ["HUMAN_REVIEW_OPEN", "CLOSED"].includes(flowStageCode.value),
);
const draftRoomReady = computed(
  () =>
    serverReviewGateReady.value &&
    Boolean(latestDraftId.value) &&
    ["HUMAN_REVIEW_OPEN", "CLOSED"].includes(flowStageCode.value),
);
const completeHearingHint = computed(
  () =>
    historyMode.value
      ? "历史庭审已封存，当前页面仅供浏览。"
      :
    serverNextStepHint.value ||
    (serverReviewGateReady.value
      ? "庭审草案已记录，本庭休庭；平台审核员将在一个工作日内处理。"
      : serverCanCompleteHearing.value
      ? "裁决草案已生成，可进入裁决草案室查阅。"
      : `当前正在进行“${flowStageMeta.value?.label || "庭审处理"}”，完成 V2 并创建审核任务后可查看草案。`),
);
const completeHearingButtonLabel = computed(() =>
  checkingDraftStatus.value
    ? "正在确认草案状态"
    : draftRoomReady.value
    ? "查看裁决草案"
    : draftReadyForResult.value
    ? "刷新草案状态"
    : "等待裁决草案",
);
const reviewHandoffTitle = computed(() =>
  serverPhaseLabel.value ||
  (serverReviewGateReady.value
    ? "本庭休庭，等待人工审核"
    : draftReadyForResult.value
    ? "裁决草案已生成，正在移交"
    : "庭审卷宗已冻结，等待裁决草案"),
);
const reviewHandoffBody = computed(() =>
  serverNextStepHint.value ||
  (serverReviewGateReady.value
    ? "庭审草案已记录，案件已进入人工审核。平台审核员将在一个工作日内完成处理，审核完成后可查看最终结果。"
    : draftReadyForResult.value
    ? "AI 法官已生成评审后的裁决草案，系统正在创建人工审核任务。"
    : "法官仅基于冻结庭审卷宗生成 V1，评审复核后再生成唯一 V2 草案。"),
);
const counterpartyLabel = computed(() =>
  role.value === "USER" ? "商家" : "用户",
);
const canSubmitAnswers = computed(
  () =>
    !historyMode.value &&
    !loadingState.hearing &&
    !hearingStreamingRuns.value.length &&
    isCaseParty.value &&
    flowStageCode.value === "PARTY_ANSWERS_OPEN" &&
    Boolean(activeIssueSetId.value) &&
    !currentActorSubmitted.value &&
    isActiveStageTimeOpen.value,
);
const activeStageDeadline = computed(
  () =>
    hearingStatus.value?.stage_deadline_at ||
    hearingStatus.value?.stageDeadlineAt ||
    "",
);
const isActiveStageTimeOpen = computed(() => {
  if (!activeStageDeadline.value) return true;
  const deadline = Date.parse(activeStageDeadline.value);
  return !Number.isFinite(deadline) || deadline > estimatedServerNowMs.value;
});
const canSupplementEvidence = computed(
  () =>
    !historyMode.value &&
    !loadingState.hearing &&
    !hearingStreamingRuns.value.length &&
    isCaseParty.value &&
    flowStageCode.value === "PARTY_EVIDENCE_OPEN" &&
    !currentActorSubmitted.value &&
    isActiveStageTimeOpen.value,
);
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
const evidenceFileIconCatalog = {
  pdf: { kind: "pdf", badge: "PDF", label: "PDF 文档材料" },
  word: { kind: "word", badge: "DOC", label: "Word 文档材料" },
  markdown: { kind: "markdown", badge: "MD", label: "Markdown 文档材料" },
  text: { kind: "text", badge: "TXT", label: "文本材料" },
  document: { kind: "document", badge: "DOC", label: "文档材料" },
  image: { kind: "image", badge: "IMG", label: "图片材料" },
  video: { kind: "video", badge: "VID", label: "视频材料" },
  other: { kind: "other", badge: "FILE", label: "其他材料" },
};
const imageExtensions = new Set(["png", "jpg", "jpeg", "webp", "gif", "bmp", "svg"]);
const videoExtensions = new Set(["mp4", "mov", "avi", "webm", "mkv", "m4v"]);
const wordExtensions = new Set(["doc", "docx"]);
const markdownExtensions = new Set(["md", "markdown"]);
const textExtensions = new Set(["txt", "csv", "log"]);
const leftEvidenceRail = computed(() =>
  role.value === "MERCHANT" ? evidenceRailProfiles.merchant : evidenceRailProfiles.user,
);
const rightEvidenceRail = computed(() =>
  leftEvidenceRail.value.key === "merchant"
    ? evidenceRailProfiles.user
    : evidenceRailProfiles.merchant,
);
const evidenceItems = computed(() => evidenceCatalog.value?.items || []);
const evidenceClerkAgentState = computed(() => {
  if (loadingState.evidence) return "THINKING";
  return evidenceItems.value.length ? "HANDOFF" : "LISTENING";
});
const hearingInitiatorRole = computed(() =>
  evidenceCatalog.value?.initiator_role ||
  evidenceCatalog.value?.initiatorRole ||
  "USER",
);
const supplementActorLabel = computed(() =>
  role.value === "MERCHANT" ? "商家" : "用户",
);
const supplementActorIsInitiator = computed(
  () => role.value === hearingInitiatorRole.value,
);
const supplementPartyCapacity = computed(() =>
  supplementActorIsInitiator.value ? "争议发起方" : "被争议方",
);
const supplementForgeryConsequence = computed(() =>
  supplementActorIsInitiator.value
    ? `经平台人工复核确认证据造假后，将驳回${supplementActorLabel.value}的全部诉求、终止争议受理并扣减信誉分。`
    : `经平台人工复核确认证据造假后，将支持并进入执行对方的全部合理诉求，并扣减${supplementActorLabel.value}的信誉分。`,
);
const supplementDeclarationReady = computed(() =>
  Boolean(
    pendingSupplementFiles.value.length &&
    supplementDeclarationForm.claimedFact.trim() &&
    supplementDeclarationForm.truthAttested,
  ),
);
const leftEvidenceItems = computed(() =>
  evidenceItemsForRole(leftEvidenceRail.value.role),
);
const rightEvidenceItems = computed(() =>
  evidenceItemsForRole(rightEvidenceRail.value.role),
);
const statementComplete = computed(() => Boolean(statementText.value.trim()));
const stageDockMode = computed(() => {
  if (reviewHandoffVisible.value) return "handoff";
  if (flowStageCode.value === "DOSSIER_FREEZING") return "sealed";
  if (isPartyInputStage(flowStageCode.value) && !currentActorSubmitted.value) return "active";
  return "waiting";
});
const stageDockTitle = computed(() => {
  if (reviewHandoffVisible.value) return reviewHandoffTitle.value;
  if (serverPhaseLabel.value) return serverPhaseLabel.value;
  if (allPartiesStageTerminal.value && isPartyInputStage(flowStageCode.value)) {
    return "双方已提交，等待系统统一整理";
  }
  if (currentActorSubmitted.value && isPartyInputStage(flowStageCode.value)) {
    return `已提交，等待${counterpartyLabel.value}`;
  }
  return flowStageMeta.value?.label || "庭审准备中";
});
const stageDockBody = computed(() => {
  if (reviewHandoffVisible.value) return reviewHandoffBody.value;
  if (serverNextStepHint.value) return serverNextStepHint.value;
  if (allPartiesStageTerminal.value && isPartyInputStage(flowStageCode.value)) {
    return "双方材料均已到达终态，系统正在封存本阶段输入并启动对应角色的全量整理。";
  }
  if (currentActorSubmitted.value && isPartyInputStage(flowStageCode.value)) {
    return `你的本阶段材料已经入卷。系统会在${counterpartyLabel.value}提交或共享倒计时结束后统一推进。`;
  }
  const descriptions = {
    COURT_PREPARING: "系统正在装载前序案情矩阵和证据矩阵。",
    CASE_INTRODUCTION: "案情接待官正在基于完整前序案情矩阵进行案情介绍。",
    EVIDENCE_INTRODUCTION: "证据书记官正在介绍双方已核验的证据及覆盖情况。",
    INTAKE_QUESTIONS_GENERATING: "案情接待官正在识别最多五个共享争议点，并生成双方视角提示。",
    PARTY_ANSWERS_OPEN: "请围绕共享争议点完整陈述本方看法。双方共享同一截止时间。",
    INTAKE_SYNTHESIZING: "案情接待官正在把双方新增内容合并进全量案情矩阵并进行完整分析。",
    EVIDENCE_REQUESTS_GENERATING: "证据书记官正在基于更新后的完整案情矩阵生成定向补证要求。",
    PARTY_EVIDENCE_OPEN: "请按补证要求一次提交本方证据批次。双方共享同一截止时间。",
    EVIDENCE_SYNTHESIZING: "证据书记官正在等待全部文件核验终态，并更新双方共享证据矩阵。",
    DOSSIER_FREEZING: "系统正在冻结案情矩阵和证据矩阵，形成法官唯一可读的庭审卷宗。",
    JUDGE_V1_GENERATING: "法官首次调用模型，正在基于冻结庭审卷宗生成 V1 裁决草案。",
    JURY_REVIEWING: "评审正在复核 V1，并将意见绑定到该版本的编号和哈希。",
    JUDGE_V2_GENERATING: "法官正在基于冻结卷宗、V1 和评审报告生成唯一 V2 草案。",
  };
  return descriptions[flowStageCode.value] || "系统正在推进庭审流程。";
});
// 业务位置：【前端庭审】formatStageClock：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function formatStageClock(deadlineAt) {
  const deadlineMs = Date.parse(deadlineAt || "");
  if (!Number.isFinite(deadlineMs)) return "20:00";
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
  if (isPartyInputStage(flowStageCode.value) && activeStageDeadline.value) {
    return { label: "共享提交时间：", value: formatStageClock(activeStageDeadline.value) };
  }
  return { label: "当前阶段：", value: flowStageMeta.value?.label || "准备中" };
});
const stageProgressItems = computed(() => hearingFlowProgress(flowStageCode.value));
const partySubmissionStatuses = computed(() =>
  [
    { role: "USER", label: "用户提交" },
    { role: "MERCHANT", label: "商家提交" },
  ].map((party) => {
    if (submittedRoles.value.includes(party.role)) {
      return { ...party, status: "已提交", tone: "submitted" };
    }
    if (!isPartyInputStage(flowStageCode.value)) {
      return { ...party, status: "未开放", tone: "sealed" };
    }
    return { ...party, status: "未提交", tone: "pending" };
  }),
);
function visibleStreamCardsForRun(run) {
  const cards = streamCardsForRun(run);
  const visibleCards = cards.filter(
    (card) => Boolean(card.content) || run.activeCardKey === card.key,
  );
  return visibleCards.length ? visibleCards : cards.slice(-1);
}

function streamCardStatusLabel(run, card) {
  if (run.status === "RECONNECTING") return "正在恢复连接";
  if (run.status === "FINALIZING") return "正在整理正式记录";
  if (["PENDING", "CONNECTING"].includes(run.status)) return "正在连接";
  if (run.activeCardKey !== card.key && card.content) return "本段生成完成";
  return "实时生成中";
}

function streamCardBadge(card, senderRole) {
  if (card.key === "adjudication-draft") return "裁决草案 V1";
  if (card.key === "adjudication-draft-v2") return "裁决草案 V2";
  if (card.key === "jury-review") return "评审复核报告";
  return transcriptBadgeForRole(senderRole);
}

const liveTranscriptItems = computed(() =>
  hearingTranscriptMessages.value
    .filter(
      (message) =>
        !isSystemAuditOnlyMessage(message) &&
        !isCounterpartyStatementWithheld(message),
    )
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
        speakerIdentity: transcriptProfileForRole(senderRole).identity || "",
        speakerName: transcriptProfileForRole(senderRole).name || "",
        senderRole,
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
const streamingTranscriptItems = computed(() =>
  visibleHearingStreamingRuns.value.flatMap((run) =>
    visibleStreamCardsForRun(run).map((card) => {
      const senderRole = card.senderRole || run.senderRole || "JUDGE";
      const profile = transcriptProfileForRole(senderRole);
      const type = profile.type;
      return {
        id: `stream-${run.runId}-${card.key}`,
        type,
        speaker: transcriptSpeakerForRole(senderRole),
        speakerIdentity: card.identity || profile.identity || "",
        speakerName: card.name || profile.name || "",
        senderRole,
        badge: streamCardBadge(card, senderRole),
        time: streamCardStatusLabel(run, card),
        text: card.content || "",
        riskLevel: type === "jury" ? "分析中" : "",
        confidenceScore: type === "jury" ? "生成中" : "",
        isFormalJuryReport: false,
        isStreaming: true,
        streamActive: run.activeCardKey === card.key,
        runId: run.runId,
        streamCardKey: card.key,
        streamStatus: run.status,
      };
    }),
  ),
);
const courtTranscriptItems = computed(() => [
  ...liveTranscriptItems.value,
  ...streamingTranscriptItems.value,
]);
const juryAgentState = computed(() => {
  if (courtTranscriptItems.value.some((item) => item.isFormalJuryReport)) {
    return "HANDOFF";
  }
  if (flowStageCode.value === "JURY_REVIEWING") {
    return "THINKING";
  }
  return "LISTENING";
});
const courtLedgerItems = computed(() => {
  const messageItems = messages.value
    .filter(
      (message) =>
        !isSystemAuditOnlyMessage(message) &&
        !isCounterpartyStatementWithheld(message),
    )
    .map((message) => ledgerItemForMessage(message))
    .filter(Boolean);
  const eventItems = caseEvents.value
    .map((event) => ledgerItemForCaseEvent(event))
    .filter(Boolean);
  return [...messageItems, ...eventItems].sort(
    (left, right) => (left.sequenceNo || 0) - (right.sequenceNo || 0),
  );
});

const transcriptRoleProfiles = {
  INTAKE_OFFICER: { type: "intake", identity: "案情接待官", name: "小衡", badge: "案情接待" },
  CUSTOMER_SERVICE: { type: "intake", identity: "案情接待官", name: "小衡", badge: "案情接待" },
  EVIDENCE_CLERK: { type: "clerk", identity: "证据书记官", name: "小册", badge: "证据归档" },
  JUDGE: { type: "judge", identity: "主审法官", name: "小正", badge: "法官宣读" },
  AI_JUDGE: { type: "judge", identity: "主审法官", name: "小正", badge: "法官宣读" },
  PRESIDING_JUDGE: { type: "judge", identity: "主审法官", name: "小正", badge: "法官宣读" },
  JURY: { type: "jury", identity: "AI 评审员", name: "小察", badge: "评审复核" },
  AI_JURY: { type: "jury", identity: "AI 评审员", name: "小察", badge: "评审复核" },
  JURY_PANEL: { type: "jury", identity: "AI 评审员", name: "小察", badge: "评审复核" },
  SYSTEM: { type: "system", speaker: "系统通知", badge: "流程状态" },
  USER: { type: "user", speaker: "用户陈述", badge: "" },
  MERCHANT: { type: "merchant", speaker: "商家陈述", badge: "" },
};

// 业务位置：【前端庭审】transcriptProfileForRole：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function transcriptProfileForRole(senderRole) {
  return transcriptRoleProfiles[senderRole] || transcriptRoleProfiles.JUDGE;
}

// 业务位置：【前端庭审】transcriptTypeForRole：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function transcriptTypeForRole(senderRole) {
  return transcriptProfileForRole(senderRole).type;
}

// 业务位置：【前端庭审】transcriptSpeakerForRole：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function transcriptSpeakerForRole(senderRole) {
  const profile = transcriptProfileForRole(senderRole);
  return profile.speaker || profile.identity || "";
}

// 业务位置：【前端庭审】transcriptBadgeForRole：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function transcriptBadgeForRole(senderRole) {
  return transcriptProfileForRole(senderRole).badge;
}

// 业务位置：【前端庭审】transcriptBadgeForMessage：围绕 房间消息和对话记录 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function transcriptBadgeForMessage(message) {
  if (messageType(message) === "JURY_REVIEW_REPORT") return "评审复核报告";
  return transcriptBadgeForRole(messageSenderRole(message));
}

// 业务位置：【前端庭审】transcriptBadgeForItem：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function transcriptBadgeForItem(item) {
  if (item.badge) return item.badge;
  if (item.type === "judge") return "法官宣读";
  if (item.type === "jury") return "评审复核";
  return "";
}

// 业务位置：【前端庭审】transcriptCharacters：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function transcriptCharacters(text) {
  return Array.from(String(text || ""));
}

// 业务位置：【前端庭审】isLongTranscript：判断 当前阶段业务数据 是否满足当前流程分支的进入条件。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function isLongTranscript(item) {
  return transcriptCharacters(item?.text).length >= LONG_TRANSCRIPT_THRESHOLD;
}

// 业务位置：【前端庭审】isTranscriptExpanded：判断 当前阶段业务数据 是否满足当前流程分支的进入条件。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function isTranscriptExpanded(item) {
  return expandedTranscriptIds.value.includes(item?.id);
}

// 业务位置：【前端庭审】visibleTranscriptText：围绕 面向当事人的业务文本 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function visibleTranscriptText(item) {
  if (!isLongTranscript(item) || isTranscriptExpanded(item)) return item?.text || "";
  return `${transcriptCharacters(item?.text)
    .slice(0, LONG_TRANSCRIPT_PREVIEW_LENGTH)
    .join("")}…`;
}

// 业务位置：【前端庭审】toggleTranscript：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function toggleTranscript(item) {
  if (!item?.id || !isLongTranscript(item)) return;
  expandedTranscriptIds.value = isTranscriptExpanded(item)
    ? expandedTranscriptIds.value.filter((id) => id !== item.id)
    : [...expandedTranscriptIds.value, item.id];
}

// 业务位置：【前端庭审】evidenceDrawerElement：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceDrawerElement(side) {
  return side === "left" ? leftEvidenceDrawer.value : rightEvidenceDrawer.value;
}

// 业务位置：【前端庭审】evidenceDrawerCloseButton：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceDrawerCloseButton(side) {
  return side === "left"
    ? leftEvidenceDrawerClose.value
    : rightEvidenceDrawerClose.value;
}

// 业务位置：【前端庭审】evidenceDrawerTrigger：执行 当前可见证据和附件 对应的业务动作，并将结果交给 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceDrawerTrigger(side) {
  return side === "left"
    ? leftEvidenceDrawerTrigger.value
    : rightEvidenceDrawerTrigger.value;
}

// 业务位置：【前端庭审】openEvidenceDrawer：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function openEvidenceDrawer(side) {
  if (!["left", "right"].includes(side)) return;
  evidenceDrawerSide.value = side;
  await nextTick();
  evidenceDrawerCloseButton(side)?.focus();
}

// 业务位置：【前端庭审】closeEvidenceDrawer：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function closeEvidenceDrawer({ restoreFocus = true } = {}) {
  const closingSide = evidenceDrawerSide.value;
  if (!closingSide) return;
  evidenceDrawerSide.value = null;
  await nextTick();
  if (restoreFocus) evidenceDrawerTrigger(closingSide)?.focus();
}

// 业务位置：【前端庭审】evidenceDrawerContainer：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceDrawerContainer() {
  return (
    hearingCourtroomPage.value?.closest?.(".room-shell__workspace") ||
    hearingCourtroomPage.value
  );
}

// 业务位置：【前端庭审】evidenceDrawerContainerWidth：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceDrawerContainerWidth(entry) {
  const contentBoxSize = entry?.contentBoxSize;
  if (Array.isArray(contentBoxSize)) {
    return Number(contentBoxSize[0]?.inlineSize) || 0;
  }
  if (contentBoxSize && typeof contentBoxSize === "object") {
    return Number(contentBoxSize.inlineSize) || 0;
  }
  return Number(entry?.contentRect?.width) || 0;
}

// 业务位置：【前端庭审】clearEvidenceDrawerForWideLayout：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function clearEvidenceDrawerForWideLayout(width) {
  if (width < EVIDENCE_DRAWER_BREAKPOINT || !evidenceDrawerSide.value) return;
  void closeEvidenceDrawer({ restoreFocus: false });
}

// 业务位置：【前端庭审】startEvidenceDrawerBreakpointObserver：启动或关闭与 当前可见证据和附件 相关的后台任务或订阅，控制运行资源和生命周期。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function startEvidenceDrawerBreakpointObserver() {
  const container = evidenceDrawerContainer();
  if (!container) return;
  if (typeof globalThis.ResizeObserver === "function") {
    evidenceDrawerResizeObserver = new globalThis.ResizeObserver((entries) => {
      const entry = entries.find((item) => item.target === container) || entries[0];
      clearEvidenceDrawerForWideLayout(evidenceDrawerContainerWidth(entry));
    });
    evidenceDrawerResizeObserver.observe(container);
    return;
  }
  evidenceDrawerWindowResizeHandler = () => {
    clearEvidenceDrawerForWideLayout(container.getBoundingClientRect().width);
  };
  window.addEventListener("resize", evidenceDrawerWindowResizeHandler);
  evidenceDrawerWindowResizeHandler();
}

// 业务位置：【前端庭审】stopEvidenceDrawerBreakpointObserver：启动或关闭与 当前可见证据和附件 相关的后台任务或订阅，控制运行资源和生命周期。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function stopEvidenceDrawerBreakpointObserver() {
  evidenceDrawerResizeObserver?.disconnect();
  evidenceDrawerResizeObserver = null;
  if (evidenceDrawerWindowResizeHandler) {
    window.removeEventListener("resize", evidenceDrawerWindowResizeHandler);
    evidenceDrawerWindowResizeHandler = null;
  }
}

// 业务位置：【前端庭审】trapEvidenceDrawerFocus：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function trapEvidenceDrawerFocus(event) {
  if (event.key !== "Tab" || !evidenceDrawerSide.value) return;
  const drawer = evidenceDrawerElement(evidenceDrawerSide.value);
  if (!drawer || event.currentTarget !== drawer) return;
  const focusable = [...drawer.querySelectorAll(
    'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
  )].filter((element) => !element.hasAttribute("hidden"));
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

// 业务位置：【前端庭审】openCourtLedger：切换与 庭审轮次和法官发言 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function openCourtLedger(event) {
  courtLedgerReturnFocus =
    event?.currentTarget || courtLedgerTrigger.value || document.activeElement;
  ledgerOpen.value = true;
  await nextTick();
  courtLedgerCloseButton.value?.focus();
}

// 业务位置：【前端庭审】closeCourtLedger：切换与 庭审轮次和法官发言 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function closeCourtLedger({ restoreFocus = true } = {}) {
  if (!ledgerOpen.value) return;
  const returnFocus = courtLedgerReturnFocus;
  ledgerOpen.value = false;
  courtLedgerReturnFocus = null;
  await nextTick();
  if (restoreFocus && returnFocus?.isConnected) returnFocus.focus();
}

// 业务位置：【前端庭审】trapCourtLedgerFocus：围绕 庭审轮次和法官发言 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function trapCourtLedgerFocus(event) {
  if (event.key !== "Tab" || !ledgerOpen.value) return;
  const drawer = courtLedgerDrawer.value;
  if (!drawer) return;
  const focusable = [...drawer.querySelectorAll(
    'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
  )].filter((element) => !element.hasAttribute("hidden"));
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (!drawer.contains(document.activeElement)) {
    event.preventDefault();
    (event.shiftKey ? last : first).focus();
  } else if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

// 业务位置：【前端庭审】handleCourtroomKeydown：执行 庭审轮次和法官发言 对应的业务动作，并将结果交给 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function handleCourtroomKeydown(event) {
  if (event.key !== "Escape") return;
  if (ledgerOpen.value) {
    event.preventDefault();
    void closeCourtLedger();
    return;
  }
  if (evidenceDrawerSide.value) {
    event.preventDefault();
    void closeEvidenceDrawer();
  }
}

// 业务位置：【前端庭审】rawMessageText：读取 房间消息和对话记录，并依据当前案件、角色和会话权限裁剪成可用输入。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function rawMessageText(message) {
  return message?.message_text || message?.messageText || message?.text || message?.content || "";
}

// 业务位置：【前端庭审】messagePayload：读取 房间消息和对话记录，并依据当前案件、角色和会话权限裁剪成可用输入。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
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

// 业务位置：【前端庭审】messageVisibility：围绕 房间消息和对话记录 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function messageVisibility(message) {
  return String(
    message?.visibility ||
      message?.visibility_scope ||
      message?.visibilityScope ||
      messagePayload(message)?.visibility ||
      "",
  ).toUpperCase();
}

// 业务位置：【前端庭审】isSystemAuditOnlyMessage：判断 房间消息和对话记录 是否满足当前流程分支的进入条件。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function isSystemAuditOnlyMessage(message) {
  return messageVisibility(message) === "SYSTEM_AUDIT_ONLY";
}

function isPartyStatementMessage(message) {
  const type = messageType(message);
  const source = String(
    message?.message_source || message?.messageSource || "",
  ).toUpperCase();
  const schema = String(
    message?.schema_version ||
      message?.schemaVersion ||
      messagePayload(message)?.schema_version ||
      "",
  ).toLowerCase();
  const actionType = String(
    message?.action_type || message?.actionType || "",
  ).toUpperCase();
  return (
    ["PARTY_STATEMENT", "HEARING_PARTY_STATEMENT"].includes(type) ||
    schema === "hearing_party_statement.v1" ||
    actionType === "PARTY_STATEMENT" ||
    (type === "PARTY_TEXT" && source === "PARTY_ACTION")
  );
}

function isCounterpartyStatementWithheld(message) {
  if (
    !isCaseParty.value ||
    flowStageCode.value !== "PARTY_ANSWERS_OPEN" ||
    allPartiesStageTerminal.value ||
    !isPartyStatementMessage(message)
  ) {
    return false;
  }
  const senderId = String(message?.sender_id || message?.senderId || "");
  if (senderId) return senderId !== String(effectiveActor.value.id);
  const senderRole = messageSenderRole(message);
  return partyRoles.includes(senderRole) && senderRole !== role.value;
}

// 业务位置：【前端庭审】juryRiskLabel：围绕 人工审核关注点和陪审团提示 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function juryRiskLabel(value) {
  const normalized = String(value || "").toUpperCase();
  return {
    LOW: "低风险",
    MEDIUM: "中风险",
    HIGH: "高风险",
  }[normalized] || "中风险";
}

// 业务位置：【前端庭审】juryConfidenceLabel：围绕 人工审核关注点和陪审团提示 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function juryConfidenceLabel(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "75/100";
  const score = numeric <= 1 ? numeric * 100 : numeric;
  return `${Math.round(score)}/100`;
}

// 业务位置：【前端庭审】transcriptTextForMessage：围绕 房间消息和对话记录 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function transcriptTextForMessage(message) {
  if (messageType(message) === "JURY_REVIEW_REPORT") {
    return formatJuryReviewReport(messagePayload(message), rawMessageText(message));
  }
  const caseMatrix = embeddedReportPayload(message, "现宣读庭前双方案情矩阵");
  if (caseMatrix) return formatCaseMatrixReport(caseMatrix);
  const evidenceMatrix = embeddedReportPayload(message, "现宣读庭前证据覆盖矩阵");
  if (evidenceMatrix) return formatEvidenceMatrixReport(evidenceMatrix);
  const text = displayRoomMessageText(sanitizeHearingCopy(rawMessageText(message)));
  if (
    messageSenderRole(message) === "EVIDENCE_CLERK" &&
    /^(证据书记官宣读证据卷宗|已完成证据装卷)/u.test(text)
  ) {
    return compactEvidenceBootstrapReport(text);
  }
  return stripTranscriptPreamble(text);
}

// 庭审卡片只展示稳定、去重的证据摘要；完整矩阵与 A2A 报告仍由后端结构化卷宗提供。
function compactEvidenceBootstrapReport(value) {
  const text = stripTranscriptPreamble(cleanPublicReportText(value));
  const evidenceCount = text.match(/(?:共\s*)?(\d+)\s*份/u)?.[1];
  const confidence = text.match(/(?:总体置信度(?:为)?|当前证据总体置信度为)\s*(\d{1,3})\s*\/\s*100/u)?.[1];
  const finding = firstReportSection(text, ["核验结论", "核心证明矩阵显示"]);
  const gap = firstReportSection(text, ["待补强", "证据交接备注"]);
  const parts = [
    evidenceCount ? `已完成证据装卷，共 ${evidenceCount} 份` : "已完成证据装卷",
    confidence ? `总体置信度 ${Math.min(Number(confidence), 100)}/100` : "",
    finding ? `核验结论：${finding}` : "",
  ];
  if (gap && !reportTextOverlaps(finding, gap)) parts.push(`待补强：${gap}`);
  return `${parts.filter(Boolean).join("。")}。`;
}

function firstReportSection(text, labels) {
  for (const label of labels) {
    const pattern = new RegExp(
      `${label}[：:]\\s*([\\s\\S]*?)(?=。(?:核验结论|核心证明矩阵显示|待补强|证据交接备注)[：:]|$)`,
      "u",
    );
    const match = String(text || "").match(pattern);
    if (match?.[1]) return compactReportSection(match[1]);
  }
  return "";
}

function cleanPublicReportText(value) {
  return String(value || "")
    .replace(/(^|[\s；;。,:：])[sS](?=[\u3400-\u9fff])/gu, "$1")
    .replace(/\bUSER\b/giu, "用户")
    .replace(/\bMERCHANT\b/giu, "商家")
    .replace(/\s+/gu, " ")
    .trim();
}

function compactReportSection(value) {
  const normalized = cleanPublicReportText(value)
    .replace(/^[\-—•·*\d.、\s]+/u, "")
    .replace(/[。；;，,\s]+$/u, "")
    .trim();
  const characters = Array.from(normalized);
  return characters.length > 84 ? `${characters.slice(0, 84).join("")}…` : normalized;
}

function reportTextOverlaps(left, right) {
  const normalize = (value) =>
    compactReportSection(value).replace(/[\s，。；：、,.!！?？…]/gu, "");
  const normalizedLeft = normalize(left);
  const normalizedRight = normalize(right);
  return Boolean(
    normalizedLeft &&
      normalizedRight &&
      (normalizedLeft.includes(normalizedRight) ||
        normalizedRight.includes(normalizedLeft)),
  );
}

function uniqueReportItems(values, existing = []) {
  const accepted = [...existing].filter(Boolean);
  const unique = [];
  for (const value of values) {
    const item = compactReportSection(value);
    if (!item || accepted.some((current) => reportTextOverlaps(current, item))) continue;
    accepted.push(item);
    unique.push(item);
  }
  return unique;
}

// 业务位置：【前端庭审】formatJuryReviewReport：将 人工审核关注点和陪审团提示 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function formatJuryReviewReport(payload, fallbackText = "") {
  if (!payload) {
    return compactReportSection(
      stripTranscriptPreamble(
        displayRoomMessageText(sanitizeHearingCopy(fallbackText)),
      ),
    );
  }
  const publicMessage = compactReportSection(
    displayRoomMessageText(sanitizeHearingCopy(payload.public_message || "")),
  );
  if (publicMessage) return publicMessage;
  const summary = compactReportSection(
    displayRoomMessageText(sanitizeHearingCopy(payload.summary || "")),
  );
  const recommendations = Array.isArray(payload.recommendations)
    ? payload.recommendations
    : payload.recommendation
      ? [payload.recommendation]
      : [];
  const conciseRecommendations = uniqueReportItems(
    recommendations.map((item) =>
      displayRoomMessageText(sanitizeHearingCopy(item)),
    ),
    [summary],
  ).slice(0, 3);
  const reviewNotes = uniqueReportItems(
    [displayRoomMessageText(sanitizeHearingCopy(payload.review_notes || ""))],
    [summary, ...conciseRecommendations],
  )[0];
  const parts = [summary];
  if (conciseRecommendations.length) {
    parts.push(`复核建议：${conciseRecommendations.join("；")}`);
  }
  if (reviewNotes) parts.push(`补充说明：${reviewNotes}`);
  return parts.filter(Boolean).join(" ") || "AI 评审员已完成复核，报告已交由法官参考。";
}

// 业务位置：【前端庭审】stripTranscriptPreamble：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function stripTranscriptPreamble(text) {
  return String(text || "").replace(
    /^(案情接待官宣读案情卷宗|证据书记官宣读证据卷宗)[：:]\s*/u,
    "",
  );
}

// 业务位置：【前端庭审】transcriptTime：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
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

// 业务位置：【前端庭审】evidenceItemsForRole：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceItemsForRole(partyRole) {
  return evidenceItems.value.filter(
    (item) =>
      evidenceSubmittedByRole(item) === partyRole &&
      evidenceSubmissionStatus(item) === "SUBMITTED",
  );
}

// 业务位置：【前端庭审】evidenceField：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceField(item, snakeCaseKey, camelCaseKey, fallback = "") {
  return item?.[snakeCaseKey] ?? item?.[camelCaseKey] ?? fallback;
}

// 业务位置：【前端庭审】statusField：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function statusField(snakeCaseKey, camelCaseKey, fallback = "") {
  return hearingStatus.value?.[snakeCaseKey] ?? hearingStatus.value?.[camelCaseKey] ?? fallback;
}

// 业务位置：【前端庭审】sanitizeHearingCopy：核验 庭审轮次和法官发言 的权限、Schema 和阶段边界，阻止越权或不完整结果进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function compactHearingEvidenceName(item) {
  const originalName = evidenceOriginalFilename(item);
  const fileName = String(originalName || "").split(/[\\/]/).pop() || "";
  const extensionIndex = fileName.lastIndexOf(".");
  const baseName = extensionIndex > 0 ? fileName.slice(0, extensionIndex) : fileName;
  const characters = Array.from(baseName.trim());
  if (!characters.length) return "该材料";
  return characters.length > 5
    ? `${characters.slice(0, 5).join("")}…`
    : characters.join("");
}

function displayHearingEvidenceReferences(value) {
  return String(value || "").replace(
    /(?:证据\s*)?EVIDENCE_[A-Za-z0-9_-]+/g,
    (reference) => {
      const internalId = reference.match(/EVIDENCE_[A-Za-z0-9_-]+/)?.[0] || "";
      const matchedEvidence = evidenceItems.value.find(
        (item) => evidenceId(item) === internalId,
      );
      return matchedEvidence
        ? `证据：${compactHearingEvidenceName(matchedEvidence)}`
        : "该证据";
    },
  );
}

function normalizeHearingPunctuation(value) {
  return String(value || "")
    .replace(/[。．]\s*[；;]+/g, "；")
    .replace(/[；;]+\s*[。．]/g, "。")
    .replace(/[；;]{2,}/g, "；")
    .replace(/([，。！？；])\1+/g, "$1")
    .replace(/\s+([，。！？；])/g, "$1")
    .trim();
}

function sanitizeHearingCopy(value) {
  const sanitized = String(value || "")
    .replace(
      "裁决草案已经进入平台审核入口，可查看结果页并等待审核员确认。",
      "裁决草案已生成，可进入裁决草案室查阅。",
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
  return normalizeHearingPunctuation(
    displayHearingEvidenceReferences(sanitized),
  );
}

// 业务位置：【前端庭审】statusHasDraftRoom：只有后端审核闸门开放且返回持久化草案 ID 时，前端才允许进入裁决草案室。
function statusHasDraftRoom(status) {
  const stage = hearingFlowStage(status || {});
  const draftId = status?.latest_draft_id ?? status?.latestDraftId;
  const reviewGateReady =
    status?.review_gate_ready ?? status?.reviewGateReady ?? false;
  return (
    Boolean(reviewGateReady) &&
    Boolean(draftId) &&
    ["HUMAN_REVIEW_OPEN", "CLOSED"].includes(stage)
  );
}

function hearingStatusSequence(status) {
  const raw = status?.stage_sequence ?? status?.stageSequence;
  if (raw === null || raw === undefined || raw === "") return null;
  const sequence = Number(raw);
  return Number.isFinite(sequence) ? sequence : null;
}

// Never let a slower, older projection close a draft gate that the page already observed open.
function applyHearingProjection(nextHearing) {
  if (!nextHearing || typeof nextHearing !== "object") return false;
  const currentStatus = hearing.value?.status || {};
  const nextStatus = nextHearing.status || {};
  const currentSequence = hearingStatusSequence(currentStatus);
  const nextSequence = hearingStatusSequence(nextStatus);

  if (
    currentSequence !== null &&
    nextSequence !== null &&
    nextSequence < currentSequence
  ) {
    return false;
  }
  if (statusHasDraftRoom(currentStatus) && !statusHasDraftRoom(nextStatus)) {
    hearing.value = { ...nextHearing, status: currentStatus };
    return false;
  }
  hearing.value = nextHearing;
  return true;
}

function waitForDraftStatusRetry(delayMs) {
  return new Promise((resolve) => {
    draftStatusRetryResolve = resolve;
    draftStatusRetryTimer = setTimeout(() => {
      draftStatusRetryTimer = null;
      draftStatusRetryResolve = null;
      resolve(true);
    }, delayMs);
  });
}

function cancelDraftStatusRetry() {
  if (draftStatusRetryTimer !== null) {
    clearTimeout(draftStatusRetryTimer);
    draftStatusRetryTimer = null;
  }
  const resolve = draftStatusRetryResolve;
  draftStatusRetryResolve = null;
  resolve?.(false);
}

async function refreshDraftStatusProjection() {
  const nextHearing = await hearingApi.hearing(effectiveActor.value, caseId.value);
  applyHearingProjection(nextHearing);
  return draftRoomReady.value;
}

// Review events can be delivered just before their final projection is visible. Recheck only
// around the V2 handoff, with a short bounded delay, so a missed commit cannot lock the page.
function synchronizeDraftRoomStatus({ force = false, immediate = false } = {}) {
  if (
    historyMode.value ||
    !draftStatusSyncEnabled ||
    draftRoomReady.value ||
    (!force && !DRAFT_STATUS_RECHECK_STAGES.has(flowStageCode.value))
  ) {
    return Promise.resolve(draftRoomReady.value);
  }
  if (draftStatusSyncPromise) return draftStatusSyncPromise;

  const delays = immediate
    ? [0, ...DRAFT_STATUS_RECHECK_DELAYS_MS]
    : DRAFT_STATUS_RECHECK_DELAYS_MS;
  checkingDraftStatus.value = true;
  draftStatusSyncPromise = (async () => {
    for (const delayMs of delays) {
      if (delayMs > 0) {
        const shouldContinue = await waitForDraftStatusRetry(delayMs);
        if (!shouldContinue) return false;
      }
      if (historyMode.value || !draftStatusSyncEnabled) return false;
      try {
        if (await refreshDraftStatusProjection()) {
          agentState.value = "HANDOFF";
          return true;
        }
      } catch {
        // A later bounded attempt may succeed after a transient disconnect.
      }
    }
    return false;
  })().finally(() => {
    checkingDraftStatus.value = false;
    draftStatusSyncPromise = null;
  });
  return draftStatusSyncPromise;
}

async function openDraftRoom({ force = false } = {}) {
  if (historyMode.value || (!force && !draftRoomReady.value)) return false;
  if (route.path === `/disputes/${caseId.value}/draft`) return true;
  await router.push(`/disputes/${caseId.value}/draft`);
  return true;
}

async function showDraftGenerationNotice() {
  draftGenerationNoticeOpen.value = true;
  await nextTick();
  draftGenerationNoticeClose.value?.focus();
}

async function closeDraftGenerationNotice({ restoreFocus = true } = {}) {
  draftGenerationNoticeOpen.value = false;
  if (!restoreFocus) return;
  await nextTick();
  draftEntryButton.value?.focus();
}

function trapDraftGenerationNoticeFocus(event) {
  const focusable = Array.from(
    draftGenerationNoticeDialog.value?.querySelectorAll("button:not(:disabled)") || [],
  );
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function embeddedReportPayload(message, marker) {
  const rawText = rawMessageText(message);
  if (!rawText || typeof rawText !== "string") return null;
  const markerIndex = rawText.indexOf(marker);
  if (markerIndex < 0) return null;
  const jsonStart = rawText.indexOf("{", markerIndex + marker.length);
  const jsonEnd = rawText.lastIndexOf("}");
  if (jsonStart < 0 || jsonEnd <= jsonStart) return null;
  try {
    return JSON.parse(rawText.slice(jsonStart, jsonEnd + 1));
  } catch {
    return null;
  }
}

function readableRole(value) {
  return String(value || "").toUpperCase() === "MERCHANT" ? "商家" : "用户";
}

function readableStance(value) {
  const normalized = String(value || "").toUpperCase();
  if (["CONFIRM", "AGREE", "ACCEPT"].includes(normalized)) return "确认";
  if (["DENY", "DISAGREE", "REJECT"].includes(normalized)) return "否认";
  if (["PARTIAL", "PARTIALLY_AGREE"].includes(normalized)) return "部分认可";
  return "未回应";
}

function readableCoverage(value) {
  const normalized = String(value || "").toUpperCase();
  if (["COVERED_BY_SUBMITTED_EVIDENCE", "COVERED_BY_FROZEN_DOSSIER"].includes(normalized)) {
    return "已有证据覆盖";
  }
  if (normalized === "PARTIALLY_COVERED_BY_FROZEN_DOSSIER") return "部分证据覆盖";
  if (normalized === "REQUIRES_HUMAN_REVIEW") return "需人工复核";
  return "尚待补充证据";
}

function formatCaseMatrixReport(matrix) {
  if (!matrix || typeof matrix !== "object") return "";
  const lines = ["庭前双方案情汇总："];
  const overview = matrix.case_overview || {};
  if (overview.neutral_summary) lines.push(`案情概览：${overview.neutral_summary}`);
  if (overview.core_conflict) lines.push(`核心争议：${overview.core_conflict}`);

  const claims = matrix.claims || {};
  const initiator = claims.initiator_claim || {};
  if (initiator.position_summary) {
    lines.push(`${readableRole(initiator.initiator_role)}主张：${initiator.position_summary}`);
  }
  const respondent = claims.respondent_direct || {};
  if (respondent.position_summary) {
    lines.push(`${readableRole(respondent.respondent_role)}主张：${respondent.position_summary}`);
  }

  const rows = Array.isArray(matrix.fact_rows) ? matrix.fact_rows : [];
  if (rows.length) lines.push("争议事实：");
  rows.slice(0, 8).forEach((row, index) => {
    const positions = row?.positions || {};
    const resolution = row?.requires_resolution ? "，待庭审核实" : "";
    lines.push(
      `${index + 1}. ${row?.fact_target || "待确认事实"}（用户：${readableStance(positions.USER?.stance)}；商家：${readableStance(positions.MERCHANT?.stance)}${resolution}）`,
    );
  });
  if (rows.length > 8) lines.push(`另有 ${rows.length - 8} 项事实已收入案情矩阵。`);
  return lines.join("\n");
}

function formatEvidenceMatrixReport(matrix) {
  if (!matrix || typeof matrix !== "object") return "";
  const rows = Array.isArray(matrix.fact_coverage) ? matrix.fact_coverage : [];
  const factTargets = new Map();
  hearingTranscriptMessages.value.forEach((message) => {
    const caseMatrix = embeddedReportPayload(message, "现宣读庭前双方案情矩阵");
    const caseRows = Array.isArray(caseMatrix?.fact_rows) ? caseMatrix.fact_rows : [];
    caseRows.forEach((row) => {
      if (row?.fact_id && row?.fact_target) factTargets.set(row.fact_id, row.fact_target);
    });
  });
  const counts = rows.reduce(
    (result, row) => {
      const status = String(row?.coverage_status || "").toUpperCase();
      if (["COVERED_BY_SUBMITTED_EVIDENCE", "COVERED_BY_FROZEN_DOSSIER"].includes(status)) {
        result.covered += 1;
      } else if (status === "PARTIALLY_COVERED_BY_FROZEN_DOSSIER") {
        result.partial += 1;
      } else if (status === "REQUIRES_HUMAN_REVIEW") {
        result.review += 1;
      } else {
        result.uncovered += 1;
      }
      return result;
    },
    { covered: 0, partial: 0, uncovered: 0, review: 0 },
  );
  const lines = [
    "庭前证据覆盖汇总：",
    `共核对 ${rows.length} 项事实：已覆盖 ${counts.covered} 项，部分覆盖 ${counts.partial} 项，待补充 ${counts.uncovered} 项，需人工复核 ${counts.review} 项。`,
  ];
  rows.slice(0, 8).forEach((row, index) => {
    const target = factTargets.get(row?.fact_id) || row?.fact_target || `第 ${index + 1} 项待确认事实`;
    lines.push(`${index + 1}. ${target}：${readableCoverage(row?.coverage_status)}`);
  });
  if (rows.length > 8) lines.push(`另有 ${rows.length - 8} 项覆盖情况已收入证据矩阵。`);
  return lines.join("\n");
}

// 业务位置：【前端庭审】evidenceSubmittedByRole：执行 当前可见证据和附件 对应的业务动作，并将结果交给 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceSubmittedByRole(item) {
  return evidenceField(item, "submitted_by_role", "submittedByRole", "");
}

// 业务位置：【前端庭审】evidenceId：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceId(item) {
  return (
    evidenceField(item, "evidence_id", "evidenceId", "") ||
    evidenceField(item, "id", "id", "")
  );
}

// 业务位置：【前端庭审】evidenceOriginalFilename：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceOriginalFilename(item) {
  return evidenceField(item, "original_filename", "originalFilename", "");
}

// 业务位置：【前端庭审】evidenceFilename：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceFilename(item) {
  return evidenceOriginalFilename(item) || evidenceId(item) || "未命名证据";
}

// 业务位置：【前端庭审】evidenceSubmissionStatus：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceSubmissionStatus(item) {
  return String(
    evidenceField(item, "submission_status", "submissionStatus", "SUBMITTED"),
  ).toUpperCase();
}

// 业务位置：【前端庭审】evidenceSubmissionStatusLabel：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceSubmissionStatusLabel(item) {
  const status = evidenceSubmissionStatus(item);
  if (status === "PENDING_SUBMISSION") return "待提交";
  if (status === "SUBMITTED") return "已提交";
  if (status === "VOIDED") return "已作废";
  if (["LOCKED", "ADMITTED", "IN_DOSSIER"].includes(status)) return "已入卷";
  return status || "待确认";
}

// 业务位置：【前端庭审】fileExtension：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function fileExtension(value) {
  const cleanValue = String(value || "").split(/[?#]/)[0];
  const fileName = cleanValue.split(/[\\/]/).pop() || "";
  const lastDotIndex = fileName.lastIndexOf(".");
  if (lastDotIndex <= 0 || lastDotIndex === fileName.length - 1) return "";
  return fileName.slice(lastDotIndex + 1).toLowerCase();
}

function evidenceFileIcon(item) {
  const extension = fileExtension(
    evidenceOriginalFilename(item) ||
      evidenceField(item, "content_url", "contentUrl", ""),
  );
  const evidenceType = String(
    evidenceField(item, "evidence_type", "evidenceType", ""),
  ).toUpperCase();
  if (extension === "pdf") return evidenceFileIconCatalog.pdf;
  if (wordExtensions.has(extension)) return evidenceFileIconCatalog.word;
  if (markdownExtensions.has(extension)) return evidenceFileIconCatalog.markdown;
  if (textExtensions.has(extension)) return evidenceFileIconCatalog.text;
  if (imageExtensions.has(extension) || ["IMAGE", "CHAT_SCREENSHOT"].includes(evidenceType)) {
    return evidenceFileIconCatalog.image;
  }
  if (videoExtensions.has(extension) || evidenceType === "VIDEO") {
    return evidenceFileIconCatalog.video;
  }
  if (["DOCUMENT", "DELIVERY_RECORD", "LOGISTICS_PROOF"].includes(evidenceType)) {
    return evidenceFileIconCatalog.document;
  }
  return evidenceFileIconCatalog.other;
}

// 业务位置：【前端庭审】evidenceCardType：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceCardType(item) {
  const kind = evidenceFileIcon(item).kind;
  if (kind === "video") return "video";
  if (kind === "image") return "image";
  return "text";
}

// 业务位置：【前端庭审】evidenceCardTone：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceCardTone(item) {
  const extension = fileExtension(evidenceOriginalFilename(item));
  const type = evidenceCardType(item);
  if (type === "video") return "gold";
  if (type === "image") return "blue";
  if (["md", "markdown"].includes(extension)) return "mint";
  return "purple";
}

// 业务位置：【前端庭审】evidenceTypeCopy：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceTypeCopy(item) {
  const type = evidenceCardType(item);
  if (type === "image") return "图片材料";
  if (type === "video") return "视频材料";
  return "文本材料";
}

// 业务位置：【前端庭审】evidenceVerificationLabel：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
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

// 业务位置：【前端庭审】evidenceConfidence：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function evidenceConfidence(item) {
  const raw = evidenceField(item, "confidence_score", "confidenceScore", null);
  if (raw === null || raw === undefined || raw === "") return "待评分";
  const numeric = Number(raw);
  if (!Number.isFinite(numeric)) return "待评分";
  const percentage = numeric <= 1 ? Math.round(numeric * 100) : Math.round(numeric);
  return `${percentage}%`;
}

// 业务位置：【前端庭审】isMissingEvidenceCatalog：判断 当前可见证据和附件 是否满足当前流程分支的进入条件。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function isMissingEvidenceCatalog(failure) {
  return ["EVIDENCE_NOT_FOUND", "RESOURCE_NOT_FOUND"].includes(failure?.code);
}

// 业务位置：【前端庭审】loadEvidenceCatalog：读取 当前可见证据和附件，并依据当前案件、角色和会话权限裁剪成可用输入。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
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

// 业务位置：【前端庭审】uploadedEvidenceId：读取 当前可见证据和附件，并依据当前案件、角色和会话权限裁剪成可用输入。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function uploadedEvidenceId(uploaded) {
  return uploaded?.evidence_id || uploaded?.evidenceId || uploaded?.id || "";
}

// 业务位置：【前端庭审】ledgerItemForMessage：围绕 房间消息和对话记录 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function ledgerItemForMessage(message) {
  const type = messageType(message);
  if (type === "PARTY_EVIDENCE_REFERENCE") {
    return {
      id: message.id || `evidence-${message.sequence_no || ""}`,
      title: "当事方补充证据",
      status: "已入卷",
      text: displayRoomMessageText(sanitizeHearingCopy(rawMessageText(message))),
      statusCode: "EVIDENCE_SUPPLEMENT",
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
      text: displayRoomMessageText(sanitizeHearingCopy(reason)),
      statusCode: "EVIDENCE_DOSSIER_REVISED",
      sequenceNo: message.sequence_no || message.sequenceNo || 0,
      tone: "matrix",
    };
  }
  if (type === "JURY_REVIEW_REPORT") {
    return {
      id: message.id || `jury-${message.sequence_no || ""}`,
      title: "评审复核报告",
      status: "已交法官",
      text: formatJuryReviewReport(messagePayload(message), rawMessageText(message)),
      statusCode: "JURY_REVIEW_REPORT",
      sequenceNo: message.sequence_no || message.sequenceNo || 0,
      tone: "jury",
    };
  }
  return null;
}

// 业务位置：【前端庭审】caseEventType：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function caseEventType(event) {
  return event?.event_type || event?.eventType || event?.event || "";
}

// 业务位置：【前端庭审】caseEventSequence：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function caseEventSequence(event) {
  return event?.sequence_no || event?.sequenceNo || event?.id || 0;
}

// 业务位置：【前端庭审】caseEventPayload：读取 Agent 流事件，并依据当前案件、角色和会话权限裁剪成可用输入。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function caseEventPayload(event) {
  const envelope = event?.data && typeof event.data === "object"
    ? event.data
    : event;
  const raw =
    envelope?.payload_json ||
    envelope?.payloadJson ||
    envelope?.event_json ||
    envelope?.eventJson ||
    envelope?.payload ||
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

// 业务位置：【前端庭审】participantRoleLabel：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function participantRoleLabel(roleValue) {
  return {
    USER: "用户",
    MERCHANT: "商家",
    PLATFORM_REVIEWER: "审核员",
    SYSTEM: "系统",
  }[String(roleValue || "").toUpperCase()] || "当事人";
}

// 业务位置：【前端庭审】ledgerItemForCaseEvent：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function ledgerItemForCaseEvent(event) {
  const type = caseEventType(event);
  const payload = caseEventPayload(event);
  const sequenceNo = caseEventSequence(event);
  if (["HEARING_ANSWER_BUNDLE_SUBMITTED", "HEARING_EVIDENCE_BATCH_SUBMITTED"].includes(type)) {
    const roleLabel = participantRoleLabel(payload.participant_role);
    const evidenceBatch = type === "HEARING_EVIDENCE_BATCH_SUBMITTED";
    return {
      id: `event-${sequenceNo}`,
      title: evidenceBatch ? "补证批次提交" : "本方陈述提交",
      status: `${roleLabel}已提交`,
      text: evidenceBatch
        ? `${roleLabel}已提交本方完整证据批次，等待另一方或共享截止时间。`
        : `${roleLabel}已提交本方完整陈述，等待另一方或共享截止时间。`,
      statusCode: type,
      sequenceNo,
      tone: evidenceBatch ? "evidence" : "matrix",
    };
  }
  if (type === "SYSTEM_STAGE_EVENT") {
    return {
      id: `event-${sequenceNo}`,
      title: displayRoomMessageText(sanitizeHearingCopy(payload.title || "庭审阶段更新")),
      status: hearingFlowStageDefinition(payload.stage_code || payload.stageCode)?.label || "流程推进",
      text: displayRoomMessageText(
        sanitizeHearingCopy(payload.description || payload.message || "系统已推进到下一庭审阶段。"),
      ),
      statusCode: type,
      sequenceNo,
      tone: "matrix",
    };
  }
  if (type === "TRIAL_DOSSIER_FROZEN") {
    return {
      id: `event-${sequenceNo}`,
      title: "庭审卷宗冻结",
      status: "已冻结",
      text: "案情矩阵和证据矩阵的版本、哈希及覆盖状态已绑定；法官只能读取该冻结卷宗。",
      statusCode: type,
      sequenceNo,
      tone: "matrix",
    };
  }
  if (["JUDGE_V1_READY", "JURY_REVIEW_READY", "JUDGE_V2_READY"].includes(type)) {
    const copy = {
      JUDGE_V1_READY: ["法官 V1 草案", "V1 已生成", "法官已基于冻结庭审卷宗生成 V1 草案。", "judge"],
      JURY_REVIEW_READY: ["评审复核报告", "已绑定 V1", "评审报告已绑定 V1 的编号和内容哈希。", "jury"],
      JUDGE_V2_READY: ["法官 V2 草案", "V2 已生成", "法官已基于冻结卷宗、V1 和评审报告生成唯一 V2。", "judge"],
    }[type];
    return {
      id: `event-${sequenceNo}`,
      title: copy[0],
      status: copy[1],
      text: copy[2],
      statusCode: type,
      sequenceNo,
      tone: copy[3],
    };
  }
  if (type === "REVIEW_TASK_CREATED") {
    return {
      id: `event-${sequenceNo}`,
      title: "人工审核任务",
      status: "已创建",
      text: "系统已把页面展示的同一份 V2 草案写入审核包，没有再次调用法官生成草案。",
      statusCode: type,
      sequenceNo,
      tone: "matrix",
    };
  }
  if (type === "EXECUTION_ASSISTANT_HANDOFF") {
    return {
      id: `event-${sequenceNo}`,
      title: "执行专员助手",
      status: "已移交",
      text: "裁决已确认，方案已移交给执行专员助手处理；当前不触发真实下游业务工具。",
      statusCode: type,
      sequenceNo,
      tone: "matrix",
    };
  }
  return null;
}

// 业务位置：【前端庭审】load：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function load() {
  try {
    const actorSnapshot = effectiveActor.value;
    if (!props.deadlineAt && !hearingDeadlineAt.value) {
      const dispute = await disputeApi.get(actorSnapshot, caseId.value);
      hearingDeadlineAt.value =
        dispute?.deadline_at ||
        dispute?.current_deadline_at ||
        dispute?.deadlineAt ||
        dispute?.currentDeadlineAt ||
        "";
    }
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
    if (shouldDiscoverActiveHearingRuns.value) {
      await resumeActiveHearingRuns();
    }
    return true;
  } catch (failure) {
    loadingState.hearing = false;
    loadingState.evidence = false;
    loadingState.messages = false;
    loadingState.events = false;
    error.value = failure.message;
    agentState.value = "ERROR";
    return false;
  }
}

// 业务位置：【前端庭审】refreshHearing：重新加载 庭审轮次和法官发言，确保页面和下一次 Agent 调用基于最新案件版本。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
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
  applyHearingProjection(nextHearing);
  messages.value = nextMessages;
  evidenceCatalog.value = nextEvidenceCatalog;
  caseEvents.value = nextEvents;
}

// 业务位置：【前端庭审】upsertRoomMessage：将 房间消息和对话记录 持久化或合并到案件快照，使 下一轮提交或裁判草案审核入口 读取到可追溯版本。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function upsertRoomMessage(message) {
  if (!message || typeof message !== "object") return;
  const index = messages.value.findIndex((item) => item.id === message.id);
  if (index >= 0) {
    messages.value.splice(index, 1, message);
    return;
  }
  messages.value.push(message);
}

// 业务位置：【前端庭审】resumeActiveHearingRuns：执行 庭审轮次和法官发言 对应的业务动作，并将结果交给 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function resumeActiveHearingRuns() {
  if (historyMode.value) return;
  const activeRuns = await loadActiveAgentRuns(
    effectiveActor.value,
    caseId.value,
    "HEARING",
  );
  await Promise.all((activeRuns || []).map((descriptor) =>
    consumeHearingAgentRun(descriptor, hearingAgentPresentation(descriptor)),
  ));
}

// 业务位置：【前端庭审】hearingAgentPresentation：围绕 庭审轮次和法官发言 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function hearingAgentPresentation(descriptor) {
  const operation = String(
    descriptor?.operation || extractAgentRunDescriptor(descriptor)?.operation || "",
  ).toUpperCase();
  if (operation.startsWith("HEARING_INTAKE_")) {
    return {
      agentLabel: "案情接待官",
      senderRole: "INTAKE_OFFICER",
    };
  }
  if (operation.startsWith("HEARING_EVIDENCE_")) {
    return {
      agentLabel: "证据书记官",
      senderRole: "EVIDENCE_CLERK",
    };
  }
  if (operation === "HEARING_JURY_REVIEW") {
    return {
      agentLabel: "AI 评审员",
      senderRole: "JURY_PANEL",
    };
  }
  return {
    agentLabel: "AI 法官",
    senderRole: "JUDGE",
  };
}

// 业务位置：【前端庭审】consumeHearingAgentRun：执行 庭审轮次和法官发言 对应的业务动作，并将结果交给 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function consumeHearingAgentRun(result, options = {}) {
  const descriptor = extractAgentRunDescriptor(result);
  if (!descriptor) return false;
  streamError.value = "";
  agentState.value = "STREAMING";
  await consumeAgentRun({
    actor: { ...effectiveActor.value },
    caseId: caseId.value,
    roomType: "HEARING",
    descriptor,
    agentLabel: options.agentLabel || "AI 法官",
    senderRole: options.senderRole || "JUDGE",
    signal: eventAbortController.signal,
    onFinal: options.onFinal || (() => refreshHearing()),
    onError: (failure) => {
      streamError.value = failure.message;
    },
  });
  if (agentState.value === "STREAMING") agentState.value = "SPEAKING";
  return true;
}

// 业务位置：【前端庭审】postMessage：执行 房间消息和对话记录 对应的业务动作，并将结果交给 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function postMessage(command) {
  if (historyMode.value) return null;
  error.value = "";
  agentState.value = "THINKING";
  try {
    const result = props.messageAction
      ? await props.messageAction(command)
      : await roomApi.postMessage(
          effectiveActor.value,
          caseId.value,
          "HEARING",
          command,
        );
    const descriptor = extractAgentRunDescriptor(result);
    const saved = descriptor ? resultRoomMessage(result) : result;
    upsertRoomMessage(saved);
    if (descriptor) await consumeHearingAgentRun(result);
    agentState.value = "SPEAKING";
    return saved || result;
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
    return null;
  }
}

async function submitPartyStatement() {
  if (!canSubmitAnswers.value || !statementComplete.value || submittingAnswers.value) return;
  const actorSnapshot = { ...effectiveActor.value };
  submittingAnswers.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const command = {
    schema_version: "hearing_party_statement.v1",
    issue_set_id: activeIssueSetId.value,
    statement_text: statementText.value.trim(),
    source_message_ids: [],
  };

  try {
    const result = props.submitAnswersAction
      ? await props.submitAnswersAction(command)
      : await hearingApi.submitStatement(actorSnapshot, caseId.value, command);
    const roomMessage = resultRoomMessage(result);
    if (roomMessage) upsertRoomMessage(roomMessage);
    if (!props.submitAnswersAction) {
      await refreshHearing();
    } else {
      hearing.value = {
        ...(hearing.value || {}),
        status: {
          ...(hearingStatus.value || {}),
          participant_statuses: optimisticParticipantStatuses(
            stageParticipantStatuses.value,
            actorSnapshot,
            "SUBMITTED",
          ),
        },
      };
    }
    agentState.value = "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    submittingAnswers.value = false;
  }
}

// 业务位置：【前端庭审】proposeSettlement：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function proposeSettlement() {
  if (historyMode.value) return;
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

function clearSupplementDeclaration() {
  if (pendingSupplementInput.value) pendingSupplementInput.value.value = "";
  pendingSupplementFiles.value = [];
  pendingSupplementInput.value = null;
  supplementDeclarationForm.claimedFact = "";
  supplementDeclarationForm.truthAttested = false;
  supplementDeclarationError.value = "";
}

function cancelSupplementDeclaration() {
  if (supplementing.value) return;
  clearSupplementDeclaration();
}

// 业务位置：【前端庭审】supplementEvidence：选择补充材料后先收集证明目标和真实性、相关性承诺，确认前不上传。
function supplementEvidence(event) {
  const files = Array.from(event.target.files || []);
  if (!files.length) return;
  if (files.length > 50) {
    error.value = "单个补充证据批次最多包含 50 份材料。";
    agentState.value = "ERROR";
    event.target.value = "";
    return;
  }
  if (!canSupplementEvidence.value) {
    error.value = "当前不在双方补证阶段、共享截止时间已到或本方批次已经提交。";
    agentState.value = "ERROR";
    event.target.value = "";
    return;
  }
  pendingSupplementFiles.value = files;
  pendingSupplementInput.value = event.target;
  supplementDeclarationForm.claimedFact = "";
  supplementDeclarationForm.truthAttested = false;
  supplementDeclarationError.value = "";
}

// 业务位置：【前端庭审】confirmSupplementEvidence：声明确认后并行上传整批庭审补充证据，再以单批次触发书记官核验和共享矩阵更新。
async function confirmSupplementEvidence() {
  const files = [...pendingSupplementFiles.value];
  if (!files.length || supplementing.value) return;
  const claimedFact = supplementDeclarationForm.claimedFact.trim();
  if (!claimedFact) {
    supplementDeclarationError.value = "请填写这批证据能够证明的具体内容。";
    return;
  }
  if (!supplementDeclarationForm.truthAttested) {
    supplementDeclarationError.value = "请阅读并勾选证据真实性与相关性承诺。";
    return;
  }
  const actorSnapshot = { ...effectiveActor.value };
  supplementing.value = true;
  error.value = "";
  supplementDeclarationError.value = "";
  try {
    const uploaded = await Promise.all(
      files.map((file) => {
        const command = {
          file,
          evidenceType: file.type.startsWith("video/")
            ? "VIDEO"
            : "OTHER",
          sourceType: evidenceSourceType.value,
          visibility: "PARTIES",
          modelProcessingAuthorized: true,
          claimedFact,
          truthAttested: true,
        };
        return props.supplementAction
          ? props.supplementAction(command)
          : evidenceApi.upload(actorSnapshot, caseId.value, command);
      }),
    );
    const attachmentIds = uploaded.map(uploadedEvidenceId).filter(Boolean);
    if (attachmentIds.length !== files.length) {
      throw new Error("部分补充证据上传后未返回证据编号，本批次尚未提交。");
    }
    if (attachmentIds.length) {
      const batchLabel = files.map((file) => file.name).join("、");
      const batchNote =
        files.length === 1
          ? `庭审补充证据：${files[0].name}`
          : `庭审补充证据（${files.length}份）：${batchLabel}`;
      const batchCommand = {
        schema_version: "hearing_evidence_batch.v1",
        request_set_id:
          evidenceRequestSet.value?.request_set_id ||
          evidenceRequestSet.value?.requestSetId,
        request_ids: applicableEvidenceRequests.value.map(
          (request) => request.request_id || request.requestId,
        ),
        evidence_ids: attachmentIds,
        batch_note: batchNote.slice(0, 1000),
      };
      const submittedBatch = props.submitEvidenceBatchAction
        ? await props.submitEvidenceBatchAction(batchCommand)
        : await hearingApi.submitEvidenceBatch(
            actorSnapshot,
            caseId.value,
            batchCommand,
          );
      const roomMessage = resultRoomMessage(submittedBatch);
      if (roomMessage && typeof roomMessage === "object") {
        upsertRoomMessage(roomMessage);
      }
      agentState.value = "SPEAKING";
    }
    if (!props.submitEvidenceBatchAction) {
      await refreshHearing();
    } else {
      hearing.value = {
        ...(hearing.value || {}),
        status: {
          ...(hearingStatus.value || {}),
          participant_statuses: optimisticParticipantStatuses(
            stageParticipantStatuses.value,
            actorSnapshot,
            "SUBMITTED",
          ),
        },
      };
    }
    clearSupplementDeclaration();
  } catch (failure) {
    error.value = failure.message;
    supplementDeclarationError.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    supplementing.value = false;
  }
}

async function submitNoEvidenceBatch() {
  if (!canSupplementEvidence.value || supplementing.value) return;
  const actorSnapshot = { ...effectiveActor.value };
  supplementing.value = true;
  error.value = "";
  try {
    const command = {
      schema_version: "hearing_evidence_batch.v1",
      request_set_id:
        evidenceRequestSet.value?.request_set_id ||
        evidenceRequestSet.value?.requestSetId,
      request_ids: applicableEvidenceRequests.value.map(
        (request) => request.request_id || request.requestId,
      ),
      evidence_ids: [],
      batch_note: "本方确认当前无其他证据可以补充。",
    };
    const result = props.submitEvidenceBatchAction
      ? await props.submitEvidenceBatchAction(command)
      : await hearingApi.submitEvidenceBatch(actorSnapshot, caseId.value, command);
    const roomMessage = resultRoomMessage(result);
    if (roomMessage) upsertRoomMessage(roomMessage);
    if (!props.submitEvidenceBatchAction) {
      await refreshHearing();
    } else {
      hearing.value = {
        ...(hearing.value || {}),
        status: {
          ...(hearingStatus.value || {}),
          participant_statuses: optimisticParticipantStatuses(
            stageParticipantStatuses.value,
            actorSnapshot,
            "SUBMITTED",
          ),
        },
      };
    }
    agentState.value = "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    supplementing.value = false;
  }
}

// 业务位置：【前端庭审】startEventStream：启动或关闭与 Agent 流事件 相关的后台任务或订阅，控制运行资源和生命周期。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function startEventStream() {
  if (historyMode.value) return;
  const streamer = props.eventStreamer || streamRoomEvents;
  void streamer({
    actor: effectiveActor.value,
    caseId: caseId.value,
    roomType: "HEARING",
    state: eventState,
    signal: eventAbortController.signal,
    snapshotLoader: refreshHearing,
    applyEvent: async (event) => {
      if (historyMode.value) return;
      const eventType = roomEventType(event);
      if (eventType === "AGENT_RUN_STARTED") {
        const payload = caseEventPayload(event);
        const operation = String(payload.operation || "").toUpperCase();
        if (HEARING_FLOW_AGENT_OPERATIONS.has(operation)) {
          void consumeHearingAgentRun(
            payload,
            hearingAgentPresentation(payload),
          ).catch(() => {});
        }
      }
      if (reviewGateEvents.has(eventType)) {
        reviewGateOpen.value = true;
        agentState.value = "HANDOFF";
        await refreshHearing();
        if (!draftRoomReady.value) {
          void synchronizeDraftRoomStatus({ force: true });
        }
      }
      if (eventType === "CASE_CLOSED") {
        await router.push(`/disputes/${caseId.value}/outcome`);
      }
    },
  });
}

// 业务位置：【前端庭审】roomEventType：围绕 Agent 流事件 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
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

// 业务位置：【前端庭审】confirmSettlement：执行 当前阶段业务数据 对应的业务动作，并将结果交给 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function confirmSettlement(version) {
  if (historyMode.value) return;
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

// 业务位置：【前端庭审】completeHearing：执行 庭审轮次和法官发言 对应的业务动作，并将结果交给 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function completeHearing() {
  if (historyMode.value || checkingDraftStatus.value) return;
  if (draftRoomReady.value) {
    agentState.value = "HANDOFF";
    await openDraftRoom();
    return;
  }
  if (!draftReadyForResult.value) return;
  checkingDraftStatus.value = true;
  error.value = "";
  agentState.value = "THINKING";
  try {
    const result = props.completeHearingAction
      ? await props.completeHearingAction()
      : await hearingApi.hearing(effectiveActor.value, caseId.value);
    const isProjection = result?.status && typeof result.status === "object";
    const status = isProjection ? result.status : result || {};
    applyHearingProjection(isProjection
      ? { ...(hearing.value || {}), ...result }
      : { ...(hearing.value || {}), status });
    if (draftRoomReady.value || statusHasDraftRoom(status)) {
      agentState.value = "HANDOFF";
      await openDraftRoom({ force: true });
      return;
    }
    await showDraftGenerationNotice();
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    checkingDraftStatus.value = false;
  }
}

// 业务位置：【前端庭审】scrollTranscriptToLatest：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
async function scrollTranscriptToLatest() {
  await nextTick();
  const rail = courtTranscriptRail.value;
  if (rail) rail.scrollTop = rail.scrollHeight;
}

// 业务位置：【前端庭审】dismissStreamError：切换与 Agent 流事件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function dismissStreamError() {
  const previous = streamError.value;
  streamError.value = "";
  if (error.value === previous) error.value = "";
  if (agentState.value === "ERROR") agentState.value = "LISTENING";
}

watch(hearingStreamingRuns, () => {
  void scrollTranscriptToLatest();
}, { deep: true });

watch(historyMode, (historical) => {
  if (!historical) {
    draftStatusSyncEnabled = true;
    if (
      props.eventStreamer ||
      props.initialHearing === null ||
      props.initialMessages === null
    ) {
      startEventStream();
    }
    return;
  }
  draftStatusSyncEnabled = false;
  cancelDraftStatusRetry();
  eventAbortController.abort();
  eventAbortController = new AbortController();
  clearAgentStreams({ caseId: caseId.value, roomType: "HEARING" });
  settlementOpen.value = false;
  submittingAnswers.value = false;
  supplementing.value = false;
  draftGenerationNoticeOpen.value = false;
  checkingDraftStatus.value = false;
  clearSupplementDeclaration();
  proposing.value = false;
  confirmingVersion.value = null;
});

onMounted(async () => {
  window.addEventListener("keydown", handleCourtroomKeydown);
  startEvidenceDrawerBreakpointObserver();
  await load();
  if (!historyMode.value && (
    props.eventStreamer ||
    props.initialHearing === null ||
    props.initialMessages === null
  )) {
    startEventStream();
  }
  if (props.initialHearing === null && !draftRoomReady.value) {
    void synchronizeDraftRoomStatus({ immediate: true });
  }
});
onBeforeUnmount(() => {
  draftStatusSyncEnabled = false;
  cancelDraftStatusRetry();
  void closeDraftGenerationNotice({ restoreFocus: false });
  clearSupplementDeclaration();
  window.removeEventListener("keydown", handleCourtroomKeydown);
  courtLedgerReturnFocus = null;
  stopEvidenceDrawerBreakpointObserver();
  eventAbortController.abort();
  clearAgentStreams({ caseId: caseId.value, roomType: "HEARING" });
  clearInterval(stageClockTimer);
});
</script>

<template>
  <RoomShell
    eyebrow="AI NATIVE COURTROOM"
    title="AI 小法庭 · 履约争端庭审"
    subtitle="卷宗驱动庭审"
    subtitle-description="接待官澄清案情、证据书记官完成补证核验并冻结卷宗后，法官才开始裁决。"
    :case-id="caseId"
    :connection-state="connectionState"
    :history-mode="historyMode"
    history-description="庭审已经封存，陈述、补证和流程推进均已锁定；你仍可查看庭审记录与证据卷轴。"
  >
    <template #clock>
      <div data-hearing-countdown>
        <PhaseCountdown
          label="庭审总时效"
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
          data-court-agent-card="jury-a"
          :state="juryAgentState"
          name="小察"
          role="AI 评审员"
          portrait-variant="jury-a"
          message="统一复核事实、证据、规则、程序公平、方案可行性与遗漏风险。"
        />
        <DigitalHuman
          data-court-agent-card="judge"
          :state="agentState"
          name="小正"
          role="AI 法官"
          message="仅在庭审卷宗冻结后调用模型，依次生成 V1 与评审后的 V2 草案。"
        />
        <DigitalHuman
          data-court-agent-card="intake-officer"
          :state="flowStageMeta?.owner === 'INTAKE_OFFICER' ? agentState : 'LISTENING'"
          name="小迎"
          role="案情接待官"
          portrait-variant="intake-officer"
          message="介绍前序案情、识别共享争议点，并把双方陈述映射回完整案情矩阵。"
        />
        <DigitalHuman
          data-court-agent-card="evidence-clerk"
          :state="evidenceClerkAgentState"
          name="小册"
          role="证据书记官"
          message="核验双方证据来源、完整性与证明力，并维护庭审证据卷宗。"
        />
      </section>
    </template>

    <main
      ref="hearingCourtroomPage"
      class="hearing-courtroom-page"
      data-hearing-courtroom-page
      :data-viewer-role="role"
    >
      <nav class="evidence-drawer-launchers" aria-label="庭审证据抽屉">
        <button
          ref="leftEvidenceDrawerTrigger"
          type="button"
          data-open-evidence-drawer="left"
          aria-controls="hearing-evidence-drawer-left"
          :aria-expanded="evidenceDrawerSide === 'left'"
          @click="openEvidenceDrawer('left')"
        >
          {{ leftEvidenceRail.title }}
        </button>
        <button
          ref="rightEvidenceDrawerTrigger"
          type="button"
          data-open-evidence-drawer="right"
          aria-controls="hearing-evidence-drawer-right"
          :aria-expanded="evidenceDrawerSide === 'right'"
          @click="openEvidenceDrawer('right')"
        >
          {{ rightEvidenceRail.title }}
        </button>
      </nav>
      <div
        v-if="evidenceDrawerSide"
        class="evidence-drawer-backdrop"
        aria-hidden="true"
        @click="closeEvidenceDrawer()"
      ></div>
      <aside
        id="hearing-evidence-drawer-left"
        ref="leftEvidenceDrawer"
        class="party-evidence-rail party-evidence-rail--left"
        :class="[
          `party-evidence-rail--${leftEvidenceRail.key}`,
          { 'party-evidence-rail--drawer-open': evidenceDrawerSide === 'left' },
        ]"
        :data-party-evidence-rail="leftEvidenceRail.key"
        :data-evidence-drawer-open="evidenceDrawerSide === 'left' ? 'left' : undefined"
        data-rail-position="left"
        :role="evidenceDrawerSide === 'left' ? 'dialog' : undefined"
        :aria-modal="evidenceDrawerSide === 'left' ? 'true' : undefined"
        aria-labelledby="hearing-evidence-drawer-left-title"
        @keydown="trapEvidenceDrawerFocus"
      >
        <header class="party-evidence-rail__header">
          <div>
            <span>{{ leftEvidenceRail.eyebrow }}</span>
            <h2 id="hearing-evidence-drawer-left-title">{{ leftEvidenceRail.title }}</h2>
            <p>{{ leftEvidenceRail.description }}</p>
          </div>
          <b>{{ leftEvidenceRail.badge }} · {{ leftEvidenceItems.length }} 份</b>
          <button
            ref="leftEvidenceDrawerClose"
            class="evidence-drawer-close"
            type="button"
            data-close-evidence-drawer="left"
            :aria-label="`关闭${leftEvidenceRail.title}`"
            @click="closeEvidenceDrawer()"
          >
            ×
          </button>
        </header>

        <div
          class="evidence-pocket"
          :aria-label="leftEvidenceRail.ariaLabel"
          data-evidence-scroll-rail="true"
        >
          <article
            v-for="item in leftEvidenceItems"
            :key="evidenceId(item)"
            class="evidence-file-card"
            :class="`evidence-file-card--${evidenceCardTone(item)}`"
          >
            <span
              class="evidence-file-card__icon evidence-file-icon evidence-file-icon--submitted"
              :data-file-kind="evidenceFileIcon(item).kind"
              :aria-label="evidenceFileIcon(item).label"
              data-hearing-evidence-icon
            >
              <span class="evidence-file-icon__body" aria-hidden="true">
                <span class="evidence-file-icon__landscape"></span>
                <span class="evidence-file-icon__play"></span>
                <span class="evidence-file-icon__lines"></span>
              </span>
              <span class="evidence-file-icon__badge" data-file-badge>
                {{ evidenceFileIcon(item).badge }}
              </span>
            </span>
            <div>
              <strong :title="evidenceFilename(item)">{{ evidenceFilename(item) }}</strong>
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

        <footer class="party-evidence-rail__footer">
          <label
            v-if="canSupplementEvidence && leftEvidenceRail.role === role"
            class="evidence-supplement-button"
            :class="{ 'evidence-supplement-button--merchant': leftEvidenceRail.key === 'merchant' }"
            :data-supplement-evidence="leftEvidenceRail.key"
          >
            {{ supplementing ? "正在补入卷宗…" : leftEvidenceRail.supplementLabel }}
            <input type="file" multiple :disabled="supplementing" @change="supplementEvidence" />
          </label>

        </footer>
      </aside>

      <section
        class="courtroom-center courtroom-center--compact-stage"
        :class="{ 'courtroom-center--without-input': !isCaseParty }"
        :data-has-input-dock="isCaseParty"
      >
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
            class="stage-progress-board stage-progress-board--timeline"
            data-hearing-progress-track
          >
            <article
              v-for="item in stageProgressItems"
              :key="item.number"
              class="stage-progress-board__item"
              :class="`stage-progress-board__item--${item.tone}`"
              data-stage-progress-item
              :data-stage-progress-state="item.tone"
              :data-stage-connector-state="item.connectorTone"
            >
              <b :aria-label="`${item.label}：${item.status}`">
                <span
                  v-if="item.tone === 'active'"
                  class="stage-progress-board__active-spinner"
                  data-stage-active-spinner
                  aria-hidden="true"
                ></span>
              </b>
              <div>
                <span class="stage-progress-board__label">{{ item.label }}</span>
                <em class="stage-progress-board__status">{{ item.status }}</em>
              </div>
            </article>
          </div>

        </section>

        <section class="court-transcript" data-court-transcript>
          <div
            ref="courtTranscriptRail"
            class="court-transcript__messages"
            data-transcript-scroll-rail="true"
          >
            <template v-for="item in courtTranscriptItems" :key="item.id">
              <div
                v-if="item.type === 'system'"
                class="court-system-notice"
                data-court-system-notice
                :data-court-message-id="item.id"
                role="status"
              >
                <time>{{ item.time }}</time>
                <span :title="item.text">{{ item.text }}</span>
              </div>
              <article
                v-else
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
                :data-court-message-id="item.id"
                :data-long-transcript="isLongTranscript(item)"
                :data-streaming="item.isStreaming ? 'true' : undefined"
                :data-agent-run-id="item.isStreaming ? item.runId : undefined"
                :data-agent-stream-card="item.isStreaming ? item.streamCardKey : undefined"
                :data-agent-stream-status="item.isStreaming ? item.streamStatus : undefined"
                :data-agent-streaming-message="item.isStreaming ? 'true' : undefined"
                :aria-live="item.isStreaming ? 'polite' : undefined"
                :aria-busy="item.isStreaming ? item.streamActive : undefined"
              >
              <header>
                <strong>
                  <AgentSpeakerLabel
                    v-if="['judge', 'jury', 'intake', 'clerk'].includes(item.type)"
                    :role="item.senderRole"
                    :identity="item.speakerIdentity"
                    :name="item.speakerName"
                  />
                  <template v-else>{{ item.speaker }}</template>
                  <small v-if="transcriptBadgeForItem(item)">{{ transcriptBadgeForItem(item) }}</small>
                  <span v-if="item.type === 'jury'" class="court-message__jury-tags" aria-label="评审辅助指标">
                    <span>风险等级</span>
                    <em>{{ item.riskLevel }}</em>
                    <span>可信分</span>
                    <em>{{ item.confidenceScore }}</em>
                  </span>
                </strong>
                <span :class="{ 'court-message__stream-status': item.isStreaming }">
                  {{ item.time }}
                </span>
              </header>
              <p>
                <span v-if="item.text">{{ visibleTranscriptText(item) }}</span>
                <span v-else class="court-message__stream-waiting">正在组织内容</span>
                <i
                  v-if="item.streamActive"
                  class="court-message__stream-cursor"
                  aria-hidden="true"
                ></i>
              </p>
              <button
                v-if="isLongTranscript(item)"
                type="button"
                class="court-message__expand"
                data-expand-transcript
                :aria-expanded="isTranscriptExpanded(item)"
                @click="toggleTranscript(item)"
              >
                {{ isTranscriptExpanded(item) ? "收起长报告" : "查看完整长报告" }}
              </button>
              </article>
            </template>

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

        <section v-if="isCaseParty" class="stage-input-bar stage-input-bar--fixed-dock" data-stage-input-bar>
          <div class="stage-input-bar__body">
            <header class="stage-input-bar__header" data-stage-input-header>
              <div>
                <h3>当前阶段提交台</h3>
              </div>
              <div
                v-if="isPartyInputStage(flowStageCode)"
                class="stage-input-bar__party-statuses"
                data-stage-input-party-statuses
              >
                <article
                  v-for="party in partySubmissionStatuses"
                  :key="party.role"
                  class="stage-input-party-status"
                  :class="`stage-input-party-status--${party.tone}`"
                  :data-stage-input-party-status="party.role"
                >
                  <span>{{ party.label }}</span>
                  <strong>{{ party.status }}</strong>
                </article>
              </div>
            </header>
            <div
              v-if="historyMode"
              class="stage-input-bar__final-status"
              data-hearing-history-locked
            >
              <span>🔒</span>
              <div>
                <strong>历史庭审已锁定</strong>
                <small>这里只保留当时的陈述、证据和法官记录，不能再次提交或补证。</small>
              </div>
            </div>
            <div
              v-else-if="reviewHandoffVisible"
              class="stage-input-bar__final-status"
              data-stage-input-final-status
            >
              <span>🔒</span>
              <div>
                <strong>{{ reviewHandoffTitle }}</strong>
                <small>{{ reviewHandoffBody }}</small>
              </div>
            </div>
            <form
              v-else-if="flowStageCode === 'PARTY_ANSWERS_OPEN' && !currentActorSubmitted"
              class="stage-input-bar__composer"
              data-stage-input-composer
              data-answer-bundle-form
              data-party-statement-form
              @submit.prevent="submitPartyStatement"
            >
              <div class="hearing-statement-workspace">
                <section class="hearing-issue-guidance" data-hearing-issue-guidance>
                  <header>
                    <strong>争议焦点</strong>
                    <small>{{ issueGuidanceItems.length }} 项</small>
                  </header>
                  <div class="hearing-issue-guidance__list">
                    <article
                      v-for="(issue, index) in issueGuidanceItems"
                      :key="issue.id"
                      data-hearing-issue
                    >
                      <span>焦点 {{ index + 1 }}</span>
                      <strong>{{ issue.statement }}</strong>
                      <p v-if="issue.prompt" data-hearing-party-prompt>{{ issue.prompt }}</p>
                    </article>
                    <p
                      v-if="!issueGuidanceItems.length"
                      class="hearing-issue-guidance__empty"
                      data-hearing-party-prompt-empty
                    >
                      当前没有本方定向提示
                    </p>
                  </div>
                </section>
                <label class="hearing-party-statement-composer">
                  <span>本方完整陈述</span>
                  <textarea
                    v-model="statementText"
                    :disabled="!canSubmitAnswers || submittingAnswers"
                    rows="3"
                    aria-label="本方完整陈述"
                    placeholder="请用自己的话说明争议事实、责任判断和期望的处理方式。"
                  ></textarea>
                </label>
              </div>
              <div class="stage-input-bar__submit-column">
                <button
                  v-if="canSubmitAnswers"
                  type="button"
                  class="stage-input-bar__submit"
                  data-submit-answer-bundle
                  data-submit-party-statement
                  :disabled="submittingAnswers || !statementComplete"
                  @click="submitPartyStatement()"
                >
                  {{ submittingAnswers ? "正在提交…" : "提交本方陈述" }}
                </button>
              </div>
            </form>
            <div
              v-else-if="flowStageCode === 'PARTY_EVIDENCE_OPEN' && !currentActorSubmitted"
              class="hearing-evidence-request-panel"
              data-hearing-evidence-requests
            >
              <div class="hearing-evidence-request-list">
                <article
                  v-for="(request, index) in applicableEvidenceRequests"
                  :key="request.request_id || request.requestId"
                >
                  <span>补证要求 {{ index + 1 }}</span>
                  <strong>{{ request.requested_material || request.requestedMaterial || request.request_text || request.requestText }}</strong>
                  <p>{{ request.verification_goal || request.verificationGoal || "证据书记官将按关联事实核验本批材料。" }}</p>
                </article>
              </div>
              <footer>
                <small>请从我方证据匣选择材料；文件会并行上传，整批只提交一次。</small>
                <button
                  type="button"
                  :disabled="supplementing"
                  data-submit-no-evidence
                  @click="submitNoEvidenceBatch"
                >
                  当前无其他证据
                </button>
              </footer>
            </div>
            <div
              v-else-if="currentActorSubmitted && isPartyInputStage(flowStageCode)"
              class="stage-input-bar__sealed-status"
              data-stage-input-submitted
            >
              <span>✓</span>
              <div>
                <strong>本阶段材料已提交</strong>
                <small>等待对方提交或共享截止时间到达后，系统会统一封存并推进。</small>
              </div>
            </div>
            <div
              v-else
              class="stage-input-bar__sealed-status"
              data-stage-input-locked
            >
              <span>·</span>
              <div>
                <strong>{{ flowStageMeta?.label || "系统处理中" }}</strong>
                <small>{{ stageDockBody }}</small>
              </div>
            </div>
          </div>
        </section>

        <p v-if="error" class="hearing-error" role="alert">{{ error }}</p>
      </section>

      <div
        id="hearing-evidence-drawer-right"
        ref="rightEvidenceDrawer"
        class="evidence-rail-column evidence-rail-column--right"
        :class="{
          'evidence-rail-column--drawer-open': evidenceDrawerSide === 'right',
        }"
        :data-party-evidence-rail="rightEvidenceRail.key"
        :data-evidence-drawer-open="evidenceDrawerSide === 'right' ? 'right' : undefined"
        data-rail-position="right"
        :role="evidenceDrawerSide === 'right' ? 'dialog' : undefined"
        :aria-modal="evidenceDrawerSide === 'right' ? 'true' : undefined"
        aria-labelledby="hearing-evidence-drawer-right-title"
        @keydown="trapEvidenceDrawerFocus"
      >
        <aside
          class="party-evidence-rail party-evidence-rail--right"
          :class="`party-evidence-rail--${rightEvidenceRail.key}`"
        >
          <header class="party-evidence-rail__header">
            <div>
              <span>{{ rightEvidenceRail.eyebrow }}</span>
              <h2 id="hearing-evidence-drawer-right-title">{{ rightEvidenceRail.title }}</h2>
              <p>{{ rightEvidenceRail.description }}</p>
            </div>
            <b>{{ rightEvidenceRail.badge }} · {{ rightEvidenceItems.length }} 份</b>
            <button
              ref="rightEvidenceDrawerClose"
              class="evidence-drawer-close"
              type="button"
              data-close-evidence-drawer="right"
              :aria-label="`关闭${rightEvidenceRail.title}`"
              @click="closeEvidenceDrawer()"
            >
              ×
            </button>
          </header>

          <div
            class="evidence-pocket"
            :aria-label="rightEvidenceRail.ariaLabel"
            data-evidence-scroll-rail="true"
          >
            <article
              v-for="item in rightEvidenceItems"
              :key="evidenceId(item)"
              class="evidence-file-card"
              :class="`evidence-file-card--${evidenceCardTone(item)}`"
            >
              <span
                class="evidence-file-card__icon evidence-file-icon evidence-file-icon--submitted"
                :data-file-kind="evidenceFileIcon(item).kind"
                :aria-label="evidenceFileIcon(item).label"
                data-hearing-evidence-icon
              >
                <span class="evidence-file-icon__body" aria-hidden="true">
                  <span class="evidence-file-icon__landscape"></span>
                  <span class="evidence-file-icon__play"></span>
                  <span class="evidence-file-icon__lines"></span>
                </span>
                <span class="evidence-file-icon__badge" data-file-badge>
                  {{ evidenceFileIcon(item).badge }}
                </span>
              </span>
              <div>
                <strong :title="evidenceFilename(item)">{{ evidenceFilename(item) }}</strong>
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

        </aside>

        <div class="hearing-side-actions">
          <button
            ref="courtLedgerTrigger"
            type="button"
            class="evidence-ledger-button"
            data-open-court-ledger
            @click="openCourtLedger"
          >
            查看庭审卷轴
          </button>
          <small class="hearing-side-actions__hint" data-complete-hearing-hint>
            {{ completeHearingHint }}
          </small>
          <button
            ref="draftEntryButton"
            type="button"
            class="evidence-complete-button"
            data-complete-hearing
            :disabled="historyMode || checkingDraftStatus || (!draftRoomReady && !draftReadyForResult)"
            :title="completeHearingHint"
            @click="completeHearing"
          >
            {{ completeHearingButtonLabel }}
          </button>
        </div>
      </div>
    </main>

    <div
      v-if="draftGenerationNoticeOpen"
      class="draft-generation-notice"
      data-draft-generation-notice
      role="presentation"
      @click.self="closeDraftGenerationNotice()"
    >
      <section
        ref="draftGenerationNoticeDialog"
        class="draft-generation-notice__dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="draft-generation-notice-title"
        aria-describedby="draft-generation-notice-description"
        @keydown.esc.stop="closeDraftGenerationNotice()"
        @keydown.tab="trapDraftGenerationNoticeFocus"
      >
        <button
          ref="draftGenerationNoticeClose"
          type="button"
          class="draft-generation-notice__close"
          aria-label="关闭裁决草案生成提示"
          title="关闭"
          @click="closeDraftGenerationNotice()"
        >
          ×
        </button>
        <span class="draft-generation-notice__eyebrow">DRAFT GENERATION</span>
        <div class="draft-generation-notice__status" aria-hidden="true">
          <i /><i /><i />
        </div>
        <h2 id="draft-generation-notice-title">裁决草案生成中</h2>
        <p id="draft-generation-notice-description">
          庭审裁决草案正在生成中，请耐心等待
        </p>
        <button
          type="button"
          class="draft-generation-notice__acknowledge"
          @click="closeDraftGenerationNotice()"
        >
          我知道了
        </button>
      </section>
    </div>

    <div
      v-if="pendingSupplementFiles.length"
      class="supplement-declaration"
      data-supplement-declaration-modal
      role="dialog"
      aria-modal="true"
      aria-labelledby="supplement-declaration-title"
      @click.self="cancelSupplementDeclaration"
    >
      <form
        class="supplement-declaration__card"
        data-supplement-declaration-form
        @submit.prevent="confirmSupplementEvidence"
      >
        <header>
          <div>
            <span>EVIDENCE DECLARATION</span>
            <h2 id="supplement-declaration-title">补充证据提交声明</h2>
            <p>证据书记官将围绕你填写的证明目标重新核验并更新证据矩阵。</p>
          </div>
          <b>{{ supplementActorLabel }} · {{ supplementPartyCapacity }}</b>
        </header>

        <section
          v-for="file in pendingSupplementFiles"
          :key="`${file.name}:${file.size}:${file.lastModified}`"
          class="supplement-declaration__file"
        >
          <span aria-hidden="true">▧</span>
          <div>
            <strong :title="file.name">{{ file.name }}</strong>
            <small>{{ file.type || "未知文件类型" }} · {{ Math.max(0.1, file.size / 1024).toFixed(1) }} KB</small>
          </div>
          <em>{{ pendingSupplementFiles.length }} 份材料</em>
        </section>

        <label class="supplement-declaration__field">
          <span>本批证据证明内容 <em>必填</em></span>
          <textarea
            v-model="supplementDeclarationForm.claimedFact"
            data-supplement-claimed-fact
            maxlength="1000"
            rows="4"
            required
            autofocus
            placeholder="请明确这批材料共同用于证明的事实。"
            @input="supplementDeclarationError = ''"
          ></textarea>
          <small>{{ supplementDeclarationForm.claimedFact.length }}/1000 · 该内容是提交方主张，仍需书记官核验。</small>
        </label>

        <section class="supplement-declaration__notice">
          <strong>真实性责任告知</strong>
          <p>{{ supplementForgeryConsequence }}</p>
          <small>真实性低于 50% 标记“疑似造假”；相关性低于 50% 标记“关联度低”。两者均进入人工审核，人工确认前不执行处罚。</small>
        </section>

        <label class="supplement-declaration__attestation">
          <input
            v-model="supplementDeclarationForm.truthAttested"
            data-supplement-truth-attested
            type="checkbox"
            @change="supplementDeclarationError = ''"
          />
          <span>本人承诺所提交证据真实、完整、未伪造或篡改，且与上述证明内容具有真实关联，并已知悉处理规则。</span>
        </label>

        <p v-if="supplementDeclarationError" class="supplement-declaration__error" role="alert">
          {{ supplementDeclarationError }}
        </p>

        <footer>
          <button type="button" :disabled="supplementing" @click="cancelSupplementDeclaration">取消</button>
          <button
            type="submit"
            data-confirm-supplement-upload
            :disabled="supplementing || !supplementDeclarationReady"
          >
            {{ supplementing ? `正在处理 ${pendingSupplementFiles.length} 份材料…` : "确认声明并上传" }}
          </button>
        </footer>
      </form>
    </div>

    <AgentStreamErrorDialog
      :message="streamError"
      title="庭审数字人生成失败"
      @dismiss="dismissStreamError"
    />

    <div
      v-if="ledgerOpen"
      ref="courtLedgerDrawer"
      class="court-ledger-backdrop"
      data-court-ledger-drawer
      role="dialog"
      aria-modal="true"
      aria-label="庭审卷轴"
      @keydown="trapCourtLedgerFocus"
      @click.self="closeCourtLedger()"
    >
      <aside class="hearing-ledger">
        <header>
          <div>
            <span>TRACEABLE ROUND LEDGER</span>
            <h2>庭审卷轴</h2>
            <p>这里保存每一轮封存后的可追溯记录，用于后续复核、申诉和审核确认。</p>
          </div>
          <button
            ref="courtLedgerCloseButton"
            type="button"
            data-close-court-ledger
            aria-label="关闭庭审卷轴"
            @click="closeCourtLedger()"
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
              <span :data-action-status="item.statusCode">{{ item.status }}</span>
            </div>
            <p>{{ item.text }}</p>
          </li>
        </ol>
        <div v-if="!courtLedgerItems.length" class="hearing-ledger__empty" data-hearing-ledger-empty>
          <span aria-hidden="true">📜</span>
          <strong>庭审阶段事件生成后，系统会把可追溯记录挂在这里。</strong>
          <small>卷轴按案情澄清、证据核验、卷宗冻结和裁决评审顺序记录。</small>
        </div>
      </aside>
    </div>
  </RoomShell>
</template>

<style scoped>
.court-agent-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  align-items: stretch;
  min-width: 0;
}
.court-agent-strip :deep(.digital-human) {
  min-width: 0;
  height: var(--digital-human-card-height);
  min-height: var(--digital-human-card-height);
  box-shadow: 0 16px 38px #536c8b12;
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="judge"]) {
  background: linear-gradient(145deg, #fffaf0, #f6f8ff 56%, #eef8ff);
  border-color: #ecd9ad;
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="jury-a"]) {
  background: linear-gradient(145deg, #ffffff, #f5f0ff 58%, #f8fbff);
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="evidence-clerk"]) {
  background: linear-gradient(145deg, #f7fffc, #eef8ff 58%, #f7f4ff);
  border-color: #cfe7e1;
}
.court-agent-strip :deep(.digital-human[data-court-agent-card="intake-officer"]) {
  background: #f4fbff;
  border-color: #cfe2ef;
}
.court-agent-strip :deep(.digital-human__identity) {
  display: grid;
  gap: 5px;
  align-items: start;
}
.court-agent-strip :deep(.digital-human__identity strong) {
  font-size: 16px;
}
.court-agent-strip :deep(.digital-human__identity span) {
  margin-top: 2px;
  font-size: 11px;
}
.court-agent-strip :deep(.digital-human__identity small) {
  display: none;
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
  align-items: flex-end;
}
:deep(.room-shell__boundary) {
  display: none;
}
:deep(.room-shell__workspace) {
  container: hearing-court / inline-size;
  min-width: 0;
}
:global(.app-page:has(.hearing-courtroom-page)) {
  padding-bottom: 42px;
}
.hearing-courtroom-page {
  box-sizing: border-box;
  position: relative;
  display: grid;
  grid-template-columns: 282px minmax(620px, 1fr) 282px;
  gap: 18px;
  height: clamp(720px, calc(100dvh - 150px), 820px);
  min-width: 0;
  min-height: 720px;
  max-height: 820px;
}
.party-evidence-rail,
.courtroom-center {
  box-sizing: border-box;
  min-width: 0;
  min-height: 0;
  background: #ffffffdf;
  border: 1px solid #dfe9f4;
  box-shadow: 0 22px 56px #536c8b12;
  backdrop-filter: blur(18px);
}
.party-evidence-rail {
  position: sticky;
  top: 96px;
  display: grid;
  grid-template-rows: 88px minmax(0, 1fr) 48px;
  gap: 10px;
  height: 100%;
  padding: 14px;
  overflow: hidden;
  border-radius: 28px;
}
.party-evidence-rail--left {
  grid-column: 1;
}
.evidence-rail-column--right {
  box-sizing: border-box;
  grid-column: 3;
  display: grid;
  grid-template-rows: minmax(0, 1fr) 48px;
  gap: 10px;
  min-width: 0;
  min-height: 0;
  height: 100%;
}
.evidence-rail-column--right .party-evidence-rail {
  position: static;
  grid-template-rows: 88px minmax(0, 1fr);
  height: 100%;
  min-height: 0;
}
.party-evidence-rail--right .evidence-pocket {
  gap: 9px;
}
.party-evidence-rail__header {
  position: relative;
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: flex-start;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.party-evidence-rail__header > div {
  min-width: 0;
}
.party-evidence-rail__header span,
.stage-input-bar span,
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
.party-evidence-rail__header h2 {
  font-size: 20px;
  letter-spacing: 0;
  line-height: 1.2;
  white-space: nowrap;
}
.party-evidence-rail__header p {
  margin: 0;
  color: #8996a8;
  font-size: 11px;
  line-height: 1.45;
}
.party-evidence-rail__header b {
  flex: 0 0 auto;
  padding: 7px 10px;
  color: #53619a;
  background: #edf7ff;
  border: 1px solid #cfe8f7;
  border-radius: 999px;
  font-size: 10px;
  white-space: nowrap;
}
.party-evidence-rail--merchant .party-evidence-rail__header b {
  background: #fff3e9;
  border-color: #f4d7c8;
}
.evidence-drawer-launchers,
.evidence-drawer-backdrop,
.evidence-drawer-close {
  display: none;
}
.evidence-pocket {
  display: grid;
  align-content: start;
  gap: 12px;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 2px 3px 10px 0;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
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
  min-height: 78px;
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
  position: relative;
  display: grid;
  width: 42px;
  height: 44px;
  place-items: center;
  overflow: visible;
  background: linear-gradient(145deg, var(--file-tint, #f4f7fb), #fff);
  border: 1px solid var(--file-border, #dbe5f1);
  border-radius: 15px;
  box-shadow: inset 0 1px 0 #ffffffd9, 0 8px 18px #5b749014;
}
.evidence-file-icon[data-file-kind="pdf"] {
  --file-accent: #e86d54;
  --file-tint: #fff0ec;
  --file-border: #ffd0c6;
}
.evidence-file-icon[data-file-kind="word"] {
  --file-accent: #4d83df;
  --file-tint: #edf5ff;
  --file-border: #caddff;
}
.evidence-file-icon[data-file-kind="markdown"],
.evidence-file-icon[data-file-kind="text"],
.evidence-file-icon[data-file-kind="document"] {
  --file-accent: #7766d8;
  --file-tint: #f2efff;
  --file-border: #ddd6ff;
}
.evidence-file-icon[data-file-kind="image"] {
  --file-accent: #30a99b;
  --file-tint: #eafbf6;
  --file-border: #bfeee2;
}
.evidence-file-icon[data-file-kind="video"] {
  --file-accent: #d56a9c;
  --file-tint: #fff0f7;
  --file-border: #ffd0e3;
}
.evidence-file-icon[data-file-kind="other"] {
  --file-accent: #7890aa;
  --file-tint: #f3f7fb;
  --file-border: #dbe5ef;
}
.evidence-file-icon__body {
  position: relative;
  display: grid;
  width: 25px;
  height: 30px;
  overflow: hidden;
  place-items: center;
  background: #fff;
  border: 1px solid color-mix(in srgb, var(--file-accent, #7890aa) 28%, #ffffff);
  border-radius: 7px 7px 8px 8px;
}
.evidence-file-icon__body::before {
  position: absolute;
  top: -1px;
  right: -1px;
  width: 9px;
  height: 9px;
  content: "";
  background: linear-gradient(135deg, #ffffff 0 48%, var(--file-accent, #7890aa) 50% 100%);
  border-left: 1px solid color-mix(in srgb, var(--file-accent, #7890aa) 24%, #ffffff);
  border-bottom: 1px solid color-mix(in srgb, var(--file-accent, #7890aa) 24%, #ffffff);
  border-radius: 0 0 0 4px;
}
.evidence-file-icon__lines {
  display: grid;
  width: 14px;
  height: 12px;
  gap: 3px;
}
.evidence-file-icon__lines::before,
.evidence-file-icon__lines::after {
  display: block;
  height: 2px;
  content: "";
  background: color-mix(in srgb, var(--file-accent, #7890aa) 70%, #ffffff);
  border-radius: 999px;
}
.evidence-file-icon__landscape,
.evidence-file-icon__play {
  display: none;
}
.evidence-file-icon[data-file-kind="image"] .evidence-file-icon__body,
.evidence-file-icon[data-file-kind="video"] .evidence-file-icon__body {
  width: 28px;
  height: 24px;
  border-radius: 8px;
}
.evidence-file-icon[data-file-kind="image"] .evidence-file-icon__body::before,
.evidence-file-icon[data-file-kind="video"] .evidence-file-icon__body::before {
  display: none;
}
.evidence-file-icon[data-file-kind="image"] .evidence-file-icon__lines,
.evidence-file-icon[data-file-kind="video"] .evidence-file-icon__lines {
  display: none;
}
.evidence-file-icon[data-file-kind="image"] .evidence-file-icon__landscape {
  position: absolute;
  inset: 5px;
  display: block;
  background:
    radial-gradient(circle at 78% 25%, #ffd36f 0 3px, transparent 4px),
    linear-gradient(135deg, transparent 48%, color-mix(in srgb, var(--file-accent) 78%, #ffffff) 49% 72%, transparent 73%),
    linear-gradient(45deg, transparent 34%, color-mix(in srgb, var(--file-accent) 55%, #ffffff) 35% 62%, transparent 63%);
  border-radius: 5px;
}
.evidence-file-icon[data-file-kind="video"] .evidence-file-icon__body {
  background:
    repeating-linear-gradient(90deg, #ffffff 0 5px, color-mix(in srgb, var(--file-accent) 12%, #ffffff) 5px 8px),
    #fff;
}
.evidence-file-icon[data-file-kind="video"] .evidence-file-icon__play {
  display: block;
  width: 0;
  height: 0;
  margin-left: 2px;
  border-top: 6px solid transparent;
  border-bottom: 6px solid transparent;
  border-left: 10px solid var(--file-accent, #d56a9c);
}
.evidence-file-icon__badge {
  position: absolute;
  right: -4px;
  bottom: -4px;
  padding: 2px 4px;
  color: #fff;
  background: var(--file-accent, #7890aa);
  border: 1px solid #ffffffd9;
  border-radius: 7px;
  box-shadow: 0 4px 10px #33435c1a;
  font-size: 8px;
  font-weight: 900;
  letter-spacing: 0;
  line-height: 1;
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
  grid-template-columns: 46px minmax(0, 1fr);
  gap: 10px;
  min-height: 78px;
  padding: 8px 9px 8px 12px;
}
.party-evidence-rail--right .evidence-file-card small {
  margin-top: 3px;
}
.party-evidence-rail--right .evidence-file-card footer {
  margin-top: 5px;
}
.party-evidence-rail__footer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
  min-width: 0;
  min-height: 0;
}
.evidence-supplement-button,
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
.party-evidence-rail__footer .evidence-supplement-button {
  width: 100%;
  min-width: 0;
  height: 48px;
  padding: 0 10px;
  text-align: center;
}
.party-evidence-rail__footer > :only-child {
  grid-column: 1 / -1;
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
.evidence-supplement-button:focus-within {
  outline: 3px solid #7ec8ff80;
  outline-offset: 2px;
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
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
  min-width: 0;
  height: 48px;
  min-height: 48px;
}
.hearing-side-actions .evidence-ledger-button,
.hearing-side-actions .evidence-complete-button {
  width: 100%;
  min-width: 0;
  height: 48px;
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
.evidence-complete-button--waiting {
  color: #39755f;
  background: #e9f7f1;
  border-color: #c9e8db;
  box-shadow: none;
  cursor: default;
}
.hearing-side-actions__hint {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.draft-generation-notice {
  position: fixed;
  inset: 0;
  z-index: 76;
  display: grid;
  padding: 20px;
  place-items: center;
  background: rgba(35, 48, 65, .58);
  backdrop-filter: blur(6px);
}
.draft-generation-notice__dialog {
  position: relative;
  box-sizing: border-box;
  display: grid;
  width: min(420px, 100%);
  justify-items: center;
  padding: 28px 28px 24px;
  color: #293c52;
  background: #fff;
  border: 1px solid #d8e2ed;
  border-radius: 8px;
  box-shadow: 0 24px 70px rgba(28, 42, 60, .24);
  text-align: center;
}
.draft-generation-notice__close {
  position: absolute;
  top: 8px;
  right: 8px;
  display: grid;
  width: 44px;
  height: 44px;
  padding: 0;
  place-items: center;
  color: #68788a;
  background: transparent;
  border: 0;
  cursor: pointer;
  font-size: 24px;
}
.draft-generation-notice__eyebrow {
  color: #7185a8;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0;
}
.draft-generation-notice__status {
  display: flex;
  height: 34px;
  align-items: center;
  gap: 7px;
  margin-top: 10px;
}
.draft-generation-notice__status i {
  width: 7px;
  height: 7px;
  background: #4ba6c7;
  border-radius: 50%;
  animation: draft-generation-pulse 1.2s ease-in-out infinite;
}
.draft-generation-notice__status i:nth-child(2) { animation-delay: .15s; }
.draft-generation-notice__status i:nth-child(3) { animation-delay: .3s; }
.draft-generation-notice__dialog h2 {
  margin: 2px 0 0;
  color: #263754;
  font-size: 22px;
  letter-spacing: 0;
}
.draft-generation-notice__dialog p {
  margin: 10px 0 0;
  color: #6c7b8d;
  font-size: 14px;
  line-height: 1.65;
}
.draft-generation-notice__acknowledge {
  min-width: 118px;
  min-height: 44px;
  padding: 0 20px;
  margin-top: 22px;
  color: #fff;
  background: #287f9d;
  border: 1px solid #287f9d;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 800;
}
.draft-generation-notice__close:focus-visible,
.draft-generation-notice__acknowledge:focus-visible {
  outline: 3px solid rgba(66, 157, 191, .32);
  outline-offset: 2px;
}
@keyframes draft-generation-pulse {
  0%, 100% { opacity: .35; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-4px); }
}
.courtroom-center {
  position: relative;
  grid-column: 2;
  display: grid;
  grid-template-rows: 122px minmax(0, 1fr) 154px;
  gap: 10px;
  height: 100%;
  overflow: hidden;
  padding: 14px 16px;
  border-radius: 30px;
}
.courtroom-center--without-input {
  grid-template-rows: 122px minmax(0, 1fr);
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
.stage-progress-board {
  position: relative;
  z-index: 3;
  display: grid;
  grid-template-columns: repeat(6, minmax(82px, 1fr));
  gap: 0;
  align-self: end;
  align-items: end;
  height: 56px;
  padding: 2px 18px 0;
  overflow-x: auto;
  overflow-y: hidden;
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
.stage-progress-board__item {
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
.stage-progress-board__item::after {
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
.stage-progress-board__item[data-stage-connector-state="complete"]::after {
  background: linear-gradient(90deg, #78d9bd, #59c6a4);
}
.stage-progress-board__item[data-stage-connector-state="none"]::after {
  display: none;
}
.stage-progress-board__item b {
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
.stage-progress-board__item div {
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
  align-items: center;
  justify-content: center;
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
}
.stage-progress-board__label {
  min-width: 0;
  overflow: hidden;
  color: #34455e;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stage-progress-board__status {
  flex: 0 0 auto;
  color: #8a98ad;
  font-size: 9px;
  font-style: normal;
  font-weight: 900;
  letter-spacing: .04em;
}
.stage-progress-board__item--complete {
  color: #4c6f65;
}
.stage-progress-board__item--complete b {
  background: linear-gradient(135deg, #a8ded1, #78cbb6);
  box-shadow: 0 0 0 4px #a8ded124, 0 8px 16px #78cbb61a;
}
.stage-progress-board__item--complete .stage-progress-board__status {
  color: #5f9f8e;
}
.stage-progress-board__item--active {
  color: #34455e;
}
.stage-progress-board__item--active b {
  color: #128ec4;
  background: #edf8ff;
  box-shadow: 0 0 0 4px #e7f5ff, 0 8px 16px #4eb9e51a;
}
.stage-progress-board__active-spinner {
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
.stage-progress-board__active-spinner::after {
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
.stage-progress-board__item--active .stage-progress-board__status {
  color: #3f9fc9;
}
.stage-progress-board__item--pending {
  color: #9d7580;
}
.stage-progress-board__item--pending b {
  background: linear-gradient(135deg, #e8c6cf, #d9aebb);
  box-shadow: 0 0 0 4px #e8c6cf22, 0 8px 16px #d9aebb18;
}
.stage-progress-board__item--pending .stage-progress-board__status {
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
  overflow: hidden;
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
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
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
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  word-break: break-word;
}
.court-message__stream-status {
  color: #8a6d3f;
}
.court-message--jury .court-message__stream-status {
  color: #6b5ac5;
}
.court-message__stream-waiting {
  color: #8d8579;
}
.court-message__stream-cursor {
  display: inline-block;
  width: 2px;
  height: 1.05em;
  margin-left: 3px;
  vertical-align: -.13em;
  background: #c98635;
  border-radius: 1px;
  animation: court-message-stream-cursor .8s steps(1, end) infinite;
}
@keyframes court-message-stream-cursor {
  0%, 45% { opacity: 1; }
  46%, 100% { opacity: 0; }
}
.court-message__expand {
  align-self: flex-start;
  min-height: 36px;
  padding: 7px 11px;
  color: #536c91;
  background: #f2f7fc;
  border: 1px solid #d9e5f0;
  border-radius: 999px;
  cursor: pointer;
  font-size: 11px;
  font-weight: 900;
}
.court-system-notice {
  display: flex;
  flex: 0 0 auto;
  width: 100%;
  min-width: 0;
  min-height: 24px;
  box-sizing: border-box;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 0 16px;
  color: #8190a5;
  font-size: 11px;
  line-height: 1.5;
  text-align: center;
}
.court-system-notice time {
  flex: 0 0 auto;
  color: #9aa6b7;
  font-variant-numeric: tabular-nums;
}
.court-system-notice span {
  min-width: 0;
  max-width: min(760px, calc(100% - 64px));
  overflow: hidden;
  color: #6d7d93;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.court-message--judge {
  --court-clothing-color: #302e55;
  align-self: center;
  color: #302e55;
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--court-clothing-color) 18%, white) 0%,
    color-mix(in srgb, var(--court-clothing-color) 32%, white) 52%,
    color-mix(in srgb, var(--court-clothing-color) 48%, white) 100%
  );
  border: 1px solid var(--court-clothing-color);
  box-shadow:
    inset 0 1px 0 #fff,
    0 14px 30px #302e5524;
}
.court-message--judge header strong::before {
  margin-right: 6px;
  color: #a47724;
  content: "⚖";
}
.court-message--judge header { color: #504a70; }
.court-message--judge p { color: #302e55; }
.court-message--judge-bench-card {
  padding: 14px 18px 14px;
  border-radius: 22px 22px 26px 26px;
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
  --court-clothing-color: #95c9b6;
  color: #244f44;
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--court-clothing-color) 18%, white) 0%,
    color-mix(in srgb, var(--court-clothing-color) 42%, white) 52%,
    color-mix(in srgb, var(--court-clothing-color) 65%, white) 100%
  );
  border: 1px solid var(--court-clothing-color);
  box-shadow:
    inset 0 1px 0 #ffffff,
    0 12px 26px #4c897524;
}
.court-message--clerk {
  --court-clothing-color: #77a9e7;
  color: #12385f;
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--court-clothing-color) 15%, white) 0%,
    color-mix(in srgb, var(--court-clothing-color) 38%, white) 52%,
    color-mix(in srgb, var(--court-clothing-color) 62%, white) 100%
  );
  border: 1px solid var(--court-clothing-color);
  box-shadow:
    inset 0 1px 0 #ffffff,
    0 14px 30px #315f9626;
}
.court-message--intake header strong::before,
.court-message--clerk header strong::before {
  margin-right: 6px;
  color: #4c8975;
  content: "◆";
}
.court-message--clerk header strong::before {
  color: #315f96;
  content: "✎";
}
.court-message--intake header {
  color: #376b5c;
}
.court-message--intake p {
  color: #244f44;
}
.court-message--clerk header {
  color: #2f5d8f;
}
.court-message--clerk p {
  color: #12385f;
}
.court-message--court-staff-card {
  box-sizing: border-box;
  padding: 13px 17px 14px;
  border-radius: 24px;
}
.court-message--court-staff-card header small {
  color: #376b5c;
  background: #ffffffa8;
  border-color: #74b29c;
}
.court-message--clerk header small {
  color: #234f7d;
  background: #ffffffa8;
  border-color: #5f91cf;
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
  --court-party-card-width: 347px;
  box-sizing: border-box;
  width: min(var(--court-party-card-width), calc(100% - 24px));
  min-width: min(var(--court-party-card-width), calc(100% - 24px));
  max-width: min(var(--court-party-card-width), calc(100% - 24px));
  min-height: 61px;
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
  --court-clothing-color: #d6c2f7;
  align-self: center;
  color: #4b3476;
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--court-clothing-color) 15%, white) 0%,
    color-mix(in srgb, var(--court-clothing-color) 40%, white) 48%,
    color-mix(in srgb, var(--court-clothing-color) 65%, white) 100%
  );
  border: 1px solid var(--court-clothing-color);
  box-shadow:
    inset 0 1px 0 #fff,
    0 12px 28px #6c5db32b;
}
.court-message--jury header { color: #60478c; }
.court-message--jury p { color: #4b3476; }
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
  --court-message-min-height: 61px;
}
.court-message--user.court-message--flexible-height-card {
  --court-message-min-height: 82px;
}
.court-message--merchant.court-message--flexible-height-card {
  --court-message-min-height: 67px;
}
.court-message--tall-narrow-card.court-message--flexible-height-card {
  --court-message-min-height: 95px;
}
.court-message--jury.court-message--flexible-height-card {
  --court-message-min-height: 99px;
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
  color: #5d4289;
  background: #ffffffa8;
  border-color: #a48cdb;
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
.stage-input-bar {
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
.stage-input-bar--fixed-dock {
  box-sizing: border-box;
  height: 154px;
  min-height: 154px;
  max-height: 154px;
  overflow: hidden;
}
.stage-input-bar__body {
  min-width: 0;
}
.stage-input-bar--fixed-dock .stage-input-bar__body {
  display: grid;
  grid-template-rows: 24px 1fr;
  height: 100%;
  min-height: 0;
}
.stage-input-bar__header {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
}
.stage-input-bar h3 {
  margin: 0;
  color: #34455e;
}
.stage-input-bar__party-statuses {
  display: flex;
  flex: 0 0 auto;
  gap: 12px;
  align-items: center;
}
.stage-input-party-status {
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
.stage-input-party-status::before {
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
.stage-input-party-status strong {
  color: #34455e;
  font-size: 11px;
}
.stage-input-party-status[data-stage-input-party-status="USER"]::before {
  background: #17a8e6;
}
.stage-input-party-status[data-stage-input-party-status="USER"] strong {
  color: #128ec4;
}
.stage-input-party-status[data-stage-input-party-status="MERCHANT"]::before {
  background: #f09a62;
}
.stage-input-party-status[data-stage-input-party-status="MERCHANT"] strong {
  color: #a96128;
}
.stage-input-bar__composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  gap: 12px;
  align-items: stretch;
  margin-top: 0;
}
.stage-input-bar--fixed-dock .stage-input-bar__composer {
  height: 100px;
  min-height: 0;
  margin-top: 8px;
}
.stage-input-bar__sealed-status,
.stage-input-bar__final-status {
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
.stage-input-bar__sealed-status > span,
.stage-input-bar__final-status > span {
  display: grid;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  place-items: center;
  background: #e6f5ee;
  border: 1px solid #c5ead6;
  border-radius: 50%;
}
.stage-input-bar__sealed-status strong,
.stage-input-bar__final-status strong {
  display: block;
  color: #28415d;
  font-size: 14px;
  font-weight: 900;
}
.stage-input-bar__sealed-status small,
.stage-input-bar__final-status small {
  display: block;
  margin-top: 4px;
  color: #7d8ba0;
  font-size: 11px;
  font-weight: 700;
}
.stage-input-bar__composer textarea {
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
.stage-input-bar--fixed-dock .stage-input-bar__composer textarea {
  height: 94px;
  min-height: 94px;
  max-height: 94px;
  overflow: auto;
}
.stage-input-bar__composer textarea:disabled {
  color: #7f8ca0;
  background: #f3f7fb;
  cursor: not-allowed;
}
.stage-input-bar__submit-column {
  display: grid;
  gap: 8px;
  align-content: stretch;
}
.stage-input-bar--fixed-dock .stage-input-bar__submit-column {
  grid-auto-rows: 44px;
  align-content: start;
}
.stage-input-bar__send-button,
.stage-input-bar__lock-button,
.stage-input-bar__submit,
.stage-input-bar__settlement-button {
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
.stage-input-bar__send-button:disabled,
.stage-input-bar__submit:disabled {
  cursor: not-allowed;
  opacity: .72;
}
.stage-input-bar__submit {
  color: #28664e;
  background: #e2f8ec;
  border: 1px solid #bde8d1;
  box-shadow: none;
}
.stage-input-bar__settlement-button {
  color: #8b5272;
  background: #fff0f4;
  border: 1px solid #f2d7df;
  box-shadow: none;
}
.stage-input-bar__lock-button {
  color: #277154;
  background: #e1f6e9;
  border: 1px solid #bde8d1;
  box-shadow: none;
  cursor: not-allowed;
}
.stage-input-bar__submitted {
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
  width: 44px;
  height: 44px;
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
.hearing-error {
  position: absolute;
  right: 22px;
  bottom: 176px;
  left: 22px;
  z-index: 12;
  max-width: calc(100% - 44px);
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  padding: 10px 14px;
  margin: 0;
  color: #a94552;
  background: #fff1f2ed;
  border: 1px solid #f1c9ce;
  border-radius: 14px;
  box-shadow: 0 12px 28px #9b40551c;
}
.courtroom-center--without-input .hearing-error {
  bottom: 14px;
}
.hearing-statement-workspace {
  display: grid;
  grid-template-columns: minmax(190px, .8fr) minmax(250px, 1.2fr);
  gap: 10px;
  height: 94px;
  min-width: 0;
}
.hearing-issue-guidance {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 4px;
  min-width: 0;
  padding-right: 10px;
  border-right: 1px solid #dce7f3;
}
.hearing-issue-guidance > header {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: #4f6279;
  font-size: 10px;
  font-weight: 900;
}
.hearing-issue-guidance__list {
  display: grid;
  gap: 5px;
  min-height: 0;
  overflow-y: auto;
}
.hearing-issue-guidance__list article {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 2px 6px;
  padding-bottom: 5px;
  border-bottom: 1px solid #e5edf5;
}
.hearing-issue-guidance__list article > span {
  color: #2187ad;
  font-size: 9px;
  font-weight: 900;
}
.hearing-issue-guidance__list article > strong {
  min-width: 0;
  color: #31445d;
  font-size: 11px;
  line-height: 1.35;
}
.hearing-issue-guidance__list article > p {
  grid-column: 2;
  margin: 0;
  color: #6f7f93;
  font-size: 10px;
  line-height: 1.35;
}
.hearing-issue-guidance__empty {
  margin: 12px 0 0;
  color: #7d8ba0;
  font-size: 11px;
}
.hearing-party-statement-composer {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 4px;
  min-width: 0;
}
.hearing-party-statement-composer > span {
  color: #4f6279;
  font-size: 10px;
  font-weight: 900;
}
.stage-input-bar--fixed-dock .hearing-party-statement-composer textarea {
  height: 76px;
  min-height: 76px;
  max-height: 76px;
  padding: 9px 11px;
  border-radius: 6px;
}
.hearing-evidence-request-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 180px;
  gap: 12px;
  height: 100px;
  margin-top: 8px;
  overflow: hidden;
}
.hearing-evidence-request-list {
  display: grid;
  gap: 6px;
  overflow-y: auto;
}
.hearing-evidence-request-list > article {
  display: grid;
  gap: 3px;
  padding: 10px 12px;
  background: #f8fbff;
  border: 1px solid #dce7f3;
  border-radius: 8px;
}
.hearing-evidence-request-panel span,
.hearing-evidence-request-panel small {
  color: #7d8ba0;
  font-size: 10px;
  font-weight: 800;
}
.hearing-evidence-request-panel strong {
  color: #31445d;
  font-size: 12px;
}
.hearing-evidence-request-panel p {
  margin: 0;
  color: #66758a;
  font-size: 11px;
}
.hearing-evidence-request-panel footer {
  display: grid;
  gap: 8px;
  align-content: center;
}
.hearing-evidence-request-panel button {
  min-height: 42px;
  color: #36536f;
  background: #e8f2f8;
  border: 1px solid #cfe0eb;
  border-radius: 8px;
  font-weight: 800;
}
.supplement-declaration {
  position: fixed;
  inset: 0;
  z-index: 72;
  display: grid;
  place-items: center;
  padding: 22px;
  background: #31435a52;
  backdrop-filter: blur(10px);
}
.supplement-declaration__card {
  box-sizing: border-box;
  display: grid;
  gap: 16px;
  width: min(660px, 100%);
  max-height: calc(100dvh - 44px);
  overflow-y: auto;
  padding: 24px;
  color: #40516a;
  background: linear-gradient(145deg, #ffffff, #f7fbff 58%, #fff8ee);
  border: 1px solid #d7e4f0;
  border-radius: 26px;
  box-shadow: 0 32px 90px #23374a42;
}
.supplement-declaration__card > header {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-start;
}
.supplement-declaration__card > header span {
  color: #7185a3;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}
.supplement-declaration__card > header h2 {
  margin: 6px 0 5px;
  color: #30435e;
  font-size: 23px;
}
.supplement-declaration__card > header p {
  margin: 0;
  color: #7a899d;
  font-size: 12px;
  line-height: 1.6;
}
.supplement-declaration__card > header b {
  flex: 0 0 auto;
  padding: 7px 10px;
  color: #4f6690;
  background: #edf5ff;
  border: 1px solid #d2e3f4;
  border-radius: 999px;
  font-size: 11px;
}
.supplement-declaration__file {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 12px 14px;
  background: #f1f7fc;
  border: 1px solid #dce9f3;
  border-radius: 16px;
}
.supplement-declaration__file > span {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  color: #50769e;
  background: #fff;
  border-radius: 12px;
  font-size: 24px;
  box-shadow: 0 5px 14px #53718b18;
}
.supplement-declaration__file div {
  display: grid;
  gap: 3px;
  min-width: 0;
}
.supplement-declaration__file strong,
.supplement-declaration__file small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.supplement-declaration__file small,
.supplement-declaration__file em {
  color: #8391a3;
  font-size: 11px;
  font-style: normal;
}
.supplement-declaration__field {
  display: grid;
  gap: 7px;
  color: #53647b;
  font-size: 12px;
  font-weight: 800;
}
.supplement-declaration__field em {
  color: #c05f5f;
  font-style: normal;
}
.supplement-declaration__field textarea {
  box-sizing: border-box;
  width: 100%;
  min-height: 108px;
  padding: 12px 14px;
  color: #33465e;
  background: #fff;
  border: 1px solid #ccdce9;
  border-radius: 14px;
  outline: none;
  resize: vertical;
  line-height: 1.65;
}
.supplement-declaration__field textarea:focus {
  border-color: #789bc2;
  box-shadow: 0 0 0 3px #789bc21d;
}
.supplement-declaration__field small {
  color: #8794a5;
  font-weight: 500;
}
.supplement-declaration__notice {
  display: grid;
  gap: 6px;
  padding: 13px 15px;
  color: #735844;
  background: #fff7e9;
  border: 1px solid #efdcbc;
  border-radius: 15px;
}
.supplement-declaration__notice p,
.supplement-declaration__notice small {
  margin: 0;
  line-height: 1.6;
}
.supplement-declaration__notice p { font-size: 12px; font-weight: 750; }
.supplement-declaration__notice small { color: #907762; font-size: 11px; }
.supplement-declaration__attestation {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  align-items: flex-start;
  color: #536276;
  font-size: 12px;
  line-height: 1.65;
}
.supplement-declaration__attestation input {
  width: 17px;
  height: 17px;
  margin-top: 1px;
  accent-color: #5d83b4;
}
.supplement-declaration__error {
  margin: 0;
  padding: 8px 11px;
  color: #a94450;
  background: #fff0f1;
  border-radius: 10px;
  font-size: 12px;
}
.supplement-declaration__card > footer {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 10px;
}
.supplement-declaration__card > footer button {
  padding: 11px 14px;
  border: 0;
  border-radius: 12px;
  font-weight: 850;
  cursor: pointer;
}
.supplement-declaration__card > footer button:first-child {
  color: #607087;
  background: #eaf0f5;
}
.supplement-declaration__card > footer button:last-child {
  color: #fff;
  background: linear-gradient(135deg, #668ac0, #6b72bd);
}
.supplement-declaration__card > footer button:disabled {
  cursor: not-allowed;
  filter: grayscale(.35);
  opacity: .55;
}
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
.settlement-dialog form header button { width: 44px; height: 44px; background: #f5ecd8; border: 0; border-radius: 50%; font-size: 21px; }
.settlement-dialog label { display: grid; gap: 7px; color: #695f61; font-size: 12px; }
.settlement-dialog textarea { padding: 11px; color: #4c464d; background: #fff; border: 1px solid #e5d9bf; border-radius: 13px; resize: vertical; }
.settlement-dialog p { color: #7d7170; font-size: 11px; }
.settlement-dialog form > button { width: 100%; padding: 12px; color: #fff; background: linear-gradient(135deg, #ff8d70, #e8759a); border: 0; border-radius: 13px; font-weight: 800; }
@container hearing-court (max-width: 1219px) {
  .hearing-courtroom-page {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
    overflow: clip;
  }
  .courtroom-center {
    grid-column: 1;
    z-index: 1;
    width: 100%;
  }
  .evidence-drawer-launchers {
    position: absolute;
    top: 136px;
    right: 10px;
    left: 10px;
    z-index: 20;
    display: flex;
    justify-content: space-between;
    gap: 12px;
    pointer-events: none;
  }
  .evidence-drawer-launchers button {
    width: min(210px, 46%);
    min-width: 0;
    min-height: 44px;
    padding: 8px 12px;
    overflow: hidden;
    color: #536786;
    text-overflow: ellipsis;
    white-space: nowrap;
    background: #ffffffef;
    border: 1px solid #d9e6f2;
    border-radius: 999px;
    box-shadow: 0 12px 28px #405c8020;
    cursor: pointer;
    pointer-events: auto;
    font-size: 11px;
    font-weight: 900;
  }
  .evidence-drawer-backdrop {
    position: absolute;
    inset: 0;
    z-index: 38;
    display: block;
    background: #4055744a;
    border-radius: 30px;
    backdrop-filter: blur(7px);
  }
  .party-evidence-rail--left,
  .evidence-rail-column--right .party-evidence-rail--right {
    position: absolute;
    top: 0;
    bottom: 0;
    z-index: 40;
    width: min(360px, calc(100% - 24px));
    height: 100%;
    max-height: none;
    visibility: hidden;
    pointer-events: none;
    transition: transform .2s ease;
  }
  .party-evidence-rail--left {
    left: 0;
    grid-column: 1;
    transform: translateX(calc(-100% - 20px));
  }
  .evidence-rail-column--right {
    display: contents;
  }
  .evidence-rail-column--right .party-evidence-rail--right {
    right: 0;
    grid-column: 1;
    transform: translateX(calc(100% + 20px));
  }
  .party-evidence-rail--left[data-evidence-drawer-open="left"],
  .evidence-rail-column--right[data-evidence-drawer-open="right"] .party-evidence-rail--right {
    visibility: visible;
    pointer-events: auto;
    transform: translateX(0);
  }
  .evidence-rail-column--right .hearing-side-actions {
    grid-column: 1;
    width: 100%;
    margin-top: 10px;
    visibility: visible;
    pointer-events: auto;
  }
  .evidence-drawer-close {
    position: absolute;
    top: 0;
    right: 0;
    z-index: 4;
    display: grid;
    width: 44px;
    height: 44px;
    place-items: center;
    color: #53617a;
    background: #f4f8fc;
    border: 1px solid #dce6ef;
    border-radius: 50%;
    cursor: pointer;
    font-size: 20px;
  }
  .party-evidence-rail__header b {
    margin-right: 48px;
  }
  .court-transcript__messages {
    padding-top: 56px;
  }
}
@media (max-width: 1050px) {
  .court-agent-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 680px) {
  .court-agent-strip {
    grid-template-columns: 1fr;
  }
  .hearing-stage-dock {
    padding-right: 10px;
    padding-left: 10px;
  }
  .hearing-stage-dock__copy--stacked {
    max-width: calc(100% - 112px);
  }
  .hearing-stage-dock__copy h2 {
    font-size: 15px;
  }
  .hearing-stage-dock__clock strong {
    font-size: 17px;
  }
  .stage-progress-board {
    grid-template-columns: repeat(6, minmax(88px, 1fr));
    padding-right: 4px;
    padding-left: 4px;
  }
  .stage-progress-board__item div {
    display: grid;
    gap: 1px;
    justify-items: center;
    white-space: normal;
  }
  .stage-progress-board__label,
  .stage-progress-board__status {
    max-width: 100%;
    text-align: center;
    white-space: normal;
  }
  .court-message,
  .court-message--judge {
    width: 94%;
    max-width: 94%;
  }
  .stage-input-bar__header {
    gap: 8px;
  }
  .stage-input-bar h3 {
    font-size: 14px;
  }
  .stage-input-bar__party-statuses {
    gap: 4px;
  }
  .stage-input-party-status {
    display: grid;
    grid-template-columns: 1fr;
    gap: 0;
    padding-left: 9px;
    white-space: normal;
  }
  .stage-input-bar__composer {
    grid-template-columns: minmax(0, 1fr) 104px;
    gap: 8px;
  }
  .hearing-statement-workspace {
    grid-template-columns: minmax(120px, .75fr) minmax(0, 1.25fr);
    gap: 7px;
  }
  .hearing-issue-guidance {
    padding-right: 7px;
  }
  .stage-input-bar--fixed-dock .stage-input-bar__composer textarea {
    padding: 11px 12px;
  }
  .hearing-ledger ol {
    grid-template-columns: 1fr;
  }
}
@media (prefers-reduced-motion: reduce) {
  .draft-generation-notice__status i,
  .court-message__stream-cursor {
    animation: none;
  }
}
</style>
