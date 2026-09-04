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
  messageAgentRunId,
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
  activeRunsLoader: { type: Function, default: null },
  agentRunConsumer: { type: Function, default: null },
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
const answerTexts = reactive({});
const activeAnswerIssueIndex = ref(0);
const activeAnswerTextarea = ref(null);
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
let eventAbortController = new AbortController();
const subscribedHearingRunIds = new Set();
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
const PRELUDE_ROLE_ORDER = Object.freeze(["judge", "intake", "clerk"]);
const PRELUDE_SEEN_STORAGE_PREFIX = "hearing-prelude-seen.v1";
const PRELUDE_FRAME_MS = 28;
const PRELUDE_GAP_MS = 360;
const PRELUDE_TARGET_DURATION_MS = Object.freeze({
  judge: 20000,
  intake: 20000,
  clerk: 20000,
});
const preludeReplay = reactive({
  active: false,
  complete: false,
  currentIndex: -1,
  charactersShown: 0,
});
let preludeReplayStarted = false;
let preludeReplayTimer = null;
const mountedCaseId = String(route.params.caseId || "");
const caseId = computed(() => String(route.params.caseId || mountedCaseId));
const historyMode = computed(() => route.query.view === "history");
const shouldDiscoverActiveHearingRuns = computed(() =>
  !historyMode.value &&
  (Boolean(props.activeRunsLoader) ||
    (props.initialMessages === null &&
      props.initialHearing === null &&
      props.initialEvidenceCatalog === null &&
      !props.eventStreamer)),
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

function preludeReplayStorageKey() {
  const actorRole = String(effectiveActor.value.role || "").toUpperCase();
  if (!["USER", "MERCHANT"].includes(actorRole)) return "";
  const actorId = String(effectiveActor.value.id || "");
  const currentCaseId = String(caseId.value || "");
  if (!actorId || !currentCaseId) return "";
  return `${PRELUDE_SEEN_STORAGE_PREFIX}:${currentCaseId}:${actorRole}:${actorId}`;
}

function hasSeenPreludeReplay() {
  const key = preludeReplayStorageKey();
  if (!key) return false;
  try {
    return globalThis.sessionStorage?.getItem(key) === "seen";
  } catch {
    return false;
  }
}

function markPreludeReplaySeen() {
  const key = preludeReplayStorageKey();
  if (!key) return;
  try {
    globalThis.sessionStorage?.setItem(key, "seen");
  } catch {
    // Storage can be unavailable in privacy-restricted webviews. The current
    // component-local guard still prevents a duplicate replay until remount.
  }
}
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
const juryReviewReport = computed(() =>
  hearing.value?.jury_review_report || hearing.value?.juryReviewReport || null,
);
const flowStageCode = computed(() => hearingFlowStage(hearingStatus.value));
const flowStageMeta = computed(() => hearingFlowStageDefinition(flowStageCode.value));
const emptyTranscriptCopy = computed(() => {
  const stageCode = flowStageCode.value;
  const stageLabel = flowStageMeta.value?.label || "庭审处理";
  if (stageCode === "PARTY_ANSWERS_OPEN") {
    return {
      title: "庭审记录同步异常",
      body: "回答阶段已经打开，但正式问题记录尚未完成权威同步；提交已暂停，请刷新后重试。",
    };
  }
  if (stageCode === "PARTY_EVIDENCE_OPEN") {
    return {
      title: "等待双方补充证据",
      body: "庭审状态机已自动进入补充证据阶段；当前尚未写入可追溯材料，无需手工开庭。",
    };
  }
  if (stageCode === "CLOSED") {
    return {
      title: "暂无可追溯庭审消息",
      body: "庭审已结束，但当前没有可展示的正式庭审消息；系统不会用示例内容代替真实案卷。",
    };
  }
  return {
    title: `${stageLabel}进行中`,
    body: `庭审状态机已自动进入“${stageLabel}”；当前尚未写入可追溯消息，无需手工开庭。`,
  };
});
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

function firstBoundText(sources, fields) {
  for (const source of sources) {
    if (!source || typeof source !== "object" || Array.isArray(source)) continue;
    for (const field of fields) {
      const value = source[field];
      if (typeof value === "string" && value.trim()) return value.trim();
    }
  }
  return "";
}

function firstBoundSequence(sources, fields) {
  for (const source of sources) {
    if (!source || typeof source !== "object" || Array.isArray(source)) continue;
    for (const field of fields) {
      const value = Number(source[field]);
      if (Number.isSafeInteger(value) && value > 0) return value;
    }
  }
  return null;
}

function objectBinding(value) {
  if (value && typeof value === "object" && !Array.isArray(value)) return value;
  if (typeof value !== "string" || !value.trim().startsWith("{")) return null;
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function transcriptWatermark(source) {
  return objectBinding(source?.transcript_watermark || source?.transcriptWatermark);
}

const formalQuestionSetReady = computed(() => {
  const value = issueSet.value;
  const schema = String(value?.schema_version || value?.schemaVersion || "");
  const entries = value?.questions;
  const setId = value?.question_set_id || value?.questionSetId;
  const setHash = value?.question_set_hash || value?.questionSetHash;
  const catalogHash =
    value?.formal_issue_catalog_hash || value?.formalIssueCatalogHash;
  return (
    schema === "hearing_question_set.v4" &&
    Boolean(setId) &&
    /^[a-f0-9]{64}$/u.test(String(setHash || "")) &&
    /^[a-f0-9]{64}$/u.test(String(catalogHash || "")) &&
    Array.isArray(entries) &&
    entries.length > 0 &&
    entries.every(
      (entry) =>
        Boolean(entry?.question_id || entry?.questionId) &&
        Boolean(entry?.issue_id || entry?.issueId),
    )
  );
});

const questionTranscriptAuthority = computed(() => {
  const watermark = [
    transcriptWatermark(issueSet.value),
    transcriptWatermark(questionSet.value),
    transcriptWatermark(hearingStatus.value),
    transcriptWatermark(hearing.value),
  ].find(Boolean) || null;
  const sources = watermark ? [watermark] : [];
  return {
    setId: activeIssueSetId.value,
    watermarkDeclared: Boolean(watermark),
    messageId: firstBoundText(sources, [
      "question_message_id",
      "questionMessageId",
      "question_room_message_id",
      "questionRoomMessageId",
      "transcript_message_id",
      "transcriptMessageId",
      "message_id",
      "messageId",
    ]),
    sequence: firstBoundSequence(sources, [
      "question_message_sequence",
      "questionMessageSequence",
      "question_message_sequence_no",
      "questionMessageSequenceNo",
      "transcript_sequence_no",
      "transcriptSequenceNo",
      "sequence_no",
      "sequenceNo",
    ]),
    runId: firstBoundText(sources, [
      "question_agent_run_id",
      "questionAgentRunId",
      "source_agent_run_id",
      "sourceAgentRunId",
      "agent_run_id",
      "agentRunId",
    ]),
  };
});

function messageBindingSources(message) {
  return [
    message,
    objectBinding(message?.metadata),
    objectBinding(message?.metadata_json || message?.metadataJson),
    messagePayload(message),
  ].filter(Boolean);
}

function messageSequence(message) {
  return firstBoundSequence([message], ["sequence_no", "sequenceNo"]);
}

function isBoundDurableQuestionMessage(message) {
  if (messageSenderRole(message) !== "INTAKE_OFFICER") return false;
  if (
    !["AGENT_MESSAGE", "HEARING_INTAKE_QUESTIONS", "HEARING_QUESTION_SET"].includes(
      messageType(message),
    )
  ) {
    return false;
  }
  const messageId = firstBoundText([message], ["id", "message_id", "messageId"]);
  const sequence = messageSequence(message);
  if (!messageId || sequence === null || !rawMessageText(message).trim()) return false;

  const authority = questionTranscriptAuthority.value;
  const sources = messageBindingSources(message);
  const messageSetId = firstBoundText(sources, [
    "issue_set_id",
    "issueSetId",
    "question_set_id",
    "questionSetId",
  ]);
  if (messageSetId && messageSetId !== authority.setId) return false;

  let explicitBindings = 0;
  if (authority.messageId) {
    explicitBindings += 1;
    if (messageId !== authority.messageId) return false;
  }
  if (authority.sequence !== null) {
    explicitBindings += 1;
    if (sequence !== authority.sequence) return false;
  }
  if (authority.runId) {
    explicitBindings += 1;
    const messageRunId = firstBoundText(sources, [
      "agent_run_id",
      "agentRunId",
      "run_id",
      "runId",
    ]);
    if (messageRunId !== authority.runId) return false;
  }
  if (authority.watermarkDeclared) return explicitBindings > 0;
  return Boolean(messageSetId && messageSetId === authority.setId);
}

const boundQuestionTranscriptMessage = computed(() =>
  formalQuestionSetReady.value
    ? hearingTranscriptMessages.value.find(isBoundDurableQuestionMessage) || null
    : null,
);
const partyAnswersTranscriptSynchronized = computed(
  () => Boolean(formalQuestionSetReady.value && boundQuestionTranscriptMessage.value),
);
const partyAnswersTranscriptSyncError = computed(
  () =>
    !historyMode.value &&
    flowStageCode.value === "PARTY_ANSWERS_OPEN" &&
    !loadingState.hearing &&
    !loadingState.messages &&
    !partyAnswersTranscriptSynchronized.value,
);
const partyAnswersTranscriptSyncMessage = computed(() =>
  formalQuestionSetReady.value
    ? "正式问题集已生成，但绑定的争议接待官问题消息尚未进入可追溯聊天记录；回答提交已暂停。"
    : "回答阶段已打开，但正式问题集尚未完成协议同步；回答提交已暂停。",
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
  if (formalQuestionSetReady.value) {
    return (questionSet.value?.questions || []).map((question, index) => ({
      id: question.issue_id || question.issueId,
      questionId: question.question_id || question.questionId,
      issueId: question.issue_id || question.issueId,
      statement:
        question.question_text ||
        question.questionText ||
        question.issue_baseline?.issue_statement ||
        question.issueBaseline?.issueStatement ||
        `争议点 ${index + 1}`,
      prompt: currentActorIssuePrompt(question),
      factCount: (question.fact_ids || question.factIds || []).length,
    }));
  }
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
function issueAnswerKey(issue) {
  return issue?.questionId || issue?.id || "";
}
const activeAnswerIssue = computed(
  () => issueGuidanceItems.value[activeAnswerIssueIndex.value] || null,
);
const completedAnswerCount = computed(
  () =>
    issueGuidanceItems.value.filter((issue) =>
      Boolean(String(answerTexts[issueAnswerKey(issue)] || "").trim()),
    ).length,
);
const hasPreviousAnswerIssue = computed(() => activeAnswerIssueIndex.value > 0);
const hasNextAnswerIssue = computed(
  () => activeAnswerIssueIndex.value < issueGuidanceItems.value.length - 1,
);

watch(
  issueGuidanceItems,
  (items) => {
    activeAnswerIssueIndex.value = items.length
      ? Math.min(activeAnswerIssueIndex.value, items.length - 1)
      : 0;
  },
  { immediate: true },
);

async function focusActiveAnswerTextarea() {
  await nextTick();
  activeAnswerTextarea.value?.focus();
}

function showPreviousAnswerIssue() {
  if (!hasPreviousAnswerIssue.value) return;
  activeAnswerIssueIndex.value -= 1;
  focusActiveAnswerTextarea();
}

function showNextAnswerIssue() {
  if (!hasNextAnswerIssue.value) return;
  activeAnswerIssueIndex.value += 1;
  focusActiveAnswerTextarea();
}
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
    partyAnswersTranscriptSynchronized.value &&
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
const statementComplete = computed(
  () =>
    issueGuidanceItems.value.length > 0 &&
    issueGuidanceItems.value.every((issue) =>
      Boolean(String(answerTexts[issueAnswerKey(issue)] || "").trim()),
    ),
);
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
const hasStageDeadlineClock = computed(
  () => isPartyInputStage(flowStageCode.value) && Boolean(activeStageDeadline.value),
);
const stageProgressItems = computed(() => hearingFlowProgress(flowStageCode.value));
const stageProgressPosition = computed(() => {
  const activeIndex = stageProgressItems.value.findIndex((item) => item.tone === "active");
  if (activeIndex >= 0) {
    return activeIndex + 1;
  }
  const completedCount = stageProgressItems.value.filter(
    (item) => item.tone === "complete",
  ).length;
  return Math.max(1, Math.min(completedCount, stageProgressItems.value.length));
});
const stageDockMeta = computed(() => {
  if (hasStageDeadlineClock.value) {
    return { label: "共享提交时间：", value: formatStageClock(activeStageDeadline.value) };
  }
  return {
    label: "庭审进度",
    value: `${stageProgressPosition.value} / ${stageProgressItems.value.length}`,
  };
});
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

function canGroupAgentTranscriptMessage(message) {
  const senderRole = messageSenderRole(message);
  return (
    Boolean(messageAgentRunId(message)) &&
    !["USER", "MERCHANT", "SYSTEM"].includes(String(senderRole).toUpperCase())
  );
}

function groupedDurableTranscriptMessages(sourceMessages) {
  return sourceMessages.reduce((groups, message) => {
    const runId = messageAgentRunId(message);
    const senderRole = messageSenderRole(message);
    const previous = groups.at(-1);
    if (
      canGroupAgentTranscriptMessage(message) &&
      previous?.groupable &&
      previous.runId === runId &&
      previous.senderRole === senderRole
    ) {
      previous.messages.push(message);
      return groups;
    }
    groups.push({
      groupable: canGroupAgentTranscriptMessage(message),
      runId,
      senderRole,
      messages: [message],
    });
    return groups;
  }, []);
}

const liveTranscriptItems = computed(() => {
  const visibleMessages = [...hearingTranscriptMessages.value]
    .sort(
      (left, right) =>
        (messageSequence(left) || Number.MAX_SAFE_INTEGER) -
        (messageSequence(right) || Number.MAX_SAFE_INTEGER),
    )
    .filter(
      (message) =>
        !isSystemAuditOnlyMessage(message) &&
        !isCounterpartyStatementWithheld(message),
    )
    .filter((message) => Boolean(rawMessageText(message)));

  return groupedDurableTranscriptMessages(visibleMessages).map((group, index) => {
    const [firstMessage] = group.messages;
    const lastMessage = group.messages.at(-1);
    const type = messageType(firstMessage);
    const senderRole = group.senderRole;
    const text = group.messages
      .map((message) => transcriptTextForMessage(message))
      .filter(Boolean)
      .join("\n\n");
    const isFormalJuryReport = group.messages.some(
      (message) => messageType(message) === "JURY_REVIEW_REPORT",
    );
    const juryPayload = isFormalJuryReport
      ? juryReviewPayloadForMessage(lastMessage)
      : null;
    return {
      id:
        group.groupable && group.runId
          ? `agent-run:${group.runId}`
          : firstMessage.id || `live-message-${messageSequence(firstMessage) || index}`,
      type: transcriptTypeForRole(senderRole),
      speaker: transcriptSpeakerForRole(senderRole),
      speakerIdentity: transcriptProfileForRole(senderRole).identity || "",
      speakerName: transcriptProfileForRole(senderRole).name || "",
      senderRole,
      badge: transcriptBadgeForMessage(firstMessage),
      time: transcriptTime(firstMessage.created_at || firstMessage.createdAt),
      text,
      riskLevel: juryRiskLabel(juryReviewSource(juryPayload)?.risk_level),
      confidenceScore: juryConfidenceLabel(
        juryReviewSource(juryPayload)?.confidence_score,
      ),
      juryHighestSeverity: juryHighestSeverity(juryPayload),
      juryFindingCount: juryFindingCount(juryPayload),
      juryRevisionCount: juryRevisionCount(juryPayload),
      isFormalJuryReport,
      runId: group.runId || "",
      messageCount: group.messages.length,
      messageType: type,
      sequenceNo: messageSequence(firstMessage),
    };
  });
});
const streamingTranscriptItems = computed(() =>
  visibleHearingStreamingRuns.value.map((run) => {
    const cards = visibleStreamCardsForRun(run);
    const activeCard =
      cards.find((card) => card.key === run.activeCardKey) || cards.at(-1) || {};
    const firstCard = cards[0] || activeCard;
    const senderRole =
      activeCard.senderRole || firstCard.senderRole || run.senderRole || "JUDGE";
    const profile = transcriptProfileForRole(senderRole);
    const type = profile.type;
    return {
      id: `agent-run:${run.runId}`,
      type,
      speaker: transcriptSpeakerForRole(senderRole),
      speakerIdentity: activeCard.identity || firstCard.identity || profile.identity || "",
      speakerName: activeCard.name || firstCard.name || profile.name || "",
      senderRole,
      badge: streamCardBadge(firstCard, senderRole),
      time: streamCardStatusLabel(run, activeCard),
      text: cards
        .map((card) => card.content || "")
        .filter(Boolean)
        .join("\n\n"),
      riskLevel: type === "jury" ? "分析中" : "",
      confidenceScore: type === "jury" ? "生成中" : "",
      isFormalJuryReport: false,
      isStreaming: true,
      streamActive: Boolean(run.activeCardKey),
      runId: run.runId,
      streamCardKey: cards.length === 1 ? firstCard.key : "unified",
      streamStatus: run.status,
      messageCount: cards.length,
    };
  }),
);
const courtTranscriptItems = computed(() => [
  ...liveTranscriptItems.value,
  ...streamingTranscriptItems.value,
]);
const preludeAgentItems = computed(() => {
  const durablePrelude = liveTranscriptItems.value.filter(
    (item) =>
      item.messageType === "AGENT_MESSAGE" &&
      !item.runId &&
      PRELUDE_ROLE_ORDER.includes(item.type),
  );
  return PRELUDE_ROLE_ORDER.map((type) =>
    durablePrelude.find((item) => item.type === type),
  ).filter(Boolean);
});
const currentPreludeItem = computed(
  () => preludeAgentItems.value[preludeReplay.currentIndex] || null,
);
const presentedCourtTranscriptItems = computed(() => {
  if (!preludeReplay.active || !currentPreludeItem.value) {
    return courtTranscriptItems.value;
  }

  const currentSequence = currentPreludeItem.value.sequenceNo;
  const currentId = currentPreludeItem.value.id;
  const preludeIndexById = new Map(
    preludeAgentItems.value.map((item, index) => [item.id, index]),
  );

  return courtTranscriptItems.value
    .filter(
      (item) =>
        Number.isFinite(item.sequenceNo) && item.sequenceNo <= currentSequence,
    )
    .map((item) => {
      const preludeIndex = preludeIndexById.get(item.id);
      if (item.id === currentId) {
        return {
          ...item,
          text: transcriptCharacters(item.text)
            .slice(0, preludeReplay.charactersShown)
            .join(""),
          presentationStreaming: true,
          preludeState: "streaming",
        };
      }
      return {
        ...item,
        presentationPrelude: true,
        preludeState:
          preludeIndex !== undefined && preludeIndex < preludeReplay.currentIndex
            ? "complete"
            : "context",
      };
    });
});

function clearPreludeReplayTimer() {
  if (preludeReplayTimer !== null) {
    window.clearTimeout(preludeReplayTimer);
    preludeReplayTimer = null;
  }
}

function reducedMotionRequested() {
  if (typeof globalThis.matchMedia !== "function") return true;
  return globalThis.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

function finishPreludeReplay() {
  clearPreludeReplayTimer();
  preludeReplay.active = false;
  preludeReplay.complete = true;
  preludeReplay.currentIndex = PRELUDE_ROLE_ORDER.length - 1;
  preludeReplay.charactersShown = transcriptCharacters(
    preludeAgentItems.value.at(-1)?.text,
  ).length;
}

function advancePreludeReplay() {
  const nextIndex = preludeReplay.currentIndex + 1;
  if (nextIndex >= preludeAgentItems.value.length) {
    finishPreludeReplay();
    return;
  }
  preludeReplay.currentIndex = nextIndex;
  preludeReplay.charactersShown = 0;
  void scrollTranscriptToLatest();
  schedulePreludeReplayFrame();
}

function schedulePreludeReplayFrame() {
  clearPreludeReplayTimer();
  const item = currentPreludeItem.value;
  if (!preludeReplay.active || !item) {
    finishPreludeReplay();
    return;
  }
  const characters = transcriptCharacters(item.text);
  const targetDuration = PRELUDE_TARGET_DURATION_MS[item.type] || 1600;
  const targetFrames = Math.max(1, Math.round(targetDuration / PRELUDE_FRAME_MS));
  const charactersPerFrame = Math.max(1, Math.ceil(characters.length / targetFrames));

  preludeReplayTimer = window.setTimeout(() => {
    preludeReplay.charactersShown = Math.min(
      characters.length,
      preludeReplay.charactersShown + charactersPerFrame,
    );
    void scrollTranscriptToLatest();
    if (preludeReplay.charactersShown >= characters.length) {
      preludeReplayTimer = window.setTimeout(
        advancePreludeReplay,
        PRELUDE_GAP_MS,
      );
      return;
    }
    schedulePreludeReplayFrame();
  }, PRELUDE_FRAME_MS);
}

function startPreludeReplay() {
  if (historyMode.value) {
    finishPreludeReplay();
    return;
  }
  if (preludeReplayStarted || loadingState.messages) return;
  if (hasSeenPreludeReplay()) {
    preludeReplayStarted = true;
    finishPreludeReplay();
    return;
  }
  if (preludeAgentItems.value.length !== PRELUDE_ROLE_ORDER.length) {
    preludeReplay.complete = true;
    return;
  }
  preludeReplayStarted = true;
  markPreludeReplaySeen();
  if (reducedMotionRequested()) {
    finishPreludeReplay();
    return;
  }
  preludeReplay.active = true;
  preludeReplay.complete = false;
  preludeReplay.currentIndex = 0;
  preludeReplay.charactersShown = 0;
  void nextTick().then(() => {
    if (courtTranscriptRail.value) courtTranscriptRail.value.scrollTop = 0;
    schedulePreludeReplayFrame();
  });
}
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

const FACT_REFERENCE_PATTERN = /\bFACT_[A-Za-z0-9]+\b/gu;
const hearingFactDisplayById = computed(() => {
  const orderedIds = [];
  const labels = new Map();
  const seen = new Set();
  for (const message of hearingTranscriptMessages.value) {
    const text = String(rawMessageText(message) || "");
    for (const match of text.matchAll(
      /\b(FACT_[A-Za-z0-9]+)\s*[（(]([^（）()\n]{2,48})[）)]/gu,
    )) {
      const label = match[2].trim();
      if (label && !/^证据缺口/u.test(label)) labels.set(match[1], label);
    }
    for (const id of text.match(FACT_REFERENCE_PATTERN) || []) {
      if (seen.has(id)) continue;
      seen.add(id);
      orderedIds.push(id);
    }
  }
  return new Map(
    orderedIds.map((id, index) => {
      const ordinal = String(index + 1).padStart(2, "0");
      const label = labels.get(id);
      return [id, label ? `事实 ${ordinal}「${label}」` : `事实 ${ordinal}`];
    }),
  );
});

function displayHearingFactReferences(value) {
  return String(value || "").replace(
    FACT_REFERENCE_PATTERN,
    (reference) => hearingFactDisplayById.value.get(reference) || "关联事实",
  );
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
    ["ANSWER_BUNDLE", "HEARING_ANSWER_BUNDLE"].includes(type) ||
    schema === "hearing_answer_bundle.v4" ||
    actionType === "ANSWER_BUNDLE" ||
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
  }[normalized] || "";
}

// 业务位置：【前端庭审】juryConfidenceLabel：围绕 人工审核关注点和陪审团提示 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function juryConfidenceLabel(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return "";
  const score = numeric <= 1 ? numeric * 100 : numeric;
  return `${Math.round(score)}/100`;
}

const JURY_DIMENSION_LABELS = {
  FACT_COMPLETENESS: "事实完整性",
  EVIDENCE_CONSISTENCY: "证据一致性",
  RULE_APPLICABILITY: "规则适用性",
  PROCEDURAL_FAIRNESS: "程序公平性",
  REMEDY_FEASIBILITY: "执行方案可行性",
  RISK_AND_OMISSIONS: "风险与遗漏",
};
const JURY_SEVERITY_LABELS = {
  BLOCKER: "阻断",
  HIGH: "高",
  MEDIUM: "中",
  LOW: "低",
};
const JURY_SEVERITY_ORDER = { BLOCKER: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };

function juryReviewSource(payload) {
  if (!payload || typeof payload !== "object") return null;
  return payload.proposal && typeof payload.proposal === "object"
    ? payload.proposal
    : payload;
}

function juryReviewPayloadForMessage(message) {
  return juryReviewReport.value || messagePayload(message);
}

function juryFindingCount(payload) {
  const source = juryReviewSource(payload);
  return Array.isArray(source?.findings) ? source.findings.length : 0;
}

function juryRevisionCount(payload) {
  const source = juryReviewSource(payload);
  return Array.isArray(source?.mandatory_revisions)
    ? source.mandatory_revisions.length
    : 0;
}

function juryHighestSeverity(payload) {
  const source = juryReviewSource(payload);
  const severities = Array.isArray(source?.findings)
    ? source.findings.map((item) => String(item?.severity || "").toUpperCase())
    : [];
  const highest = severities.reduce(
    (current, candidate) =>
      (JURY_SEVERITY_ORDER[candidate] || 0) > (JURY_SEVERITY_ORDER[current] || 0)
        ? candidate
        : current,
    "",
  );
  return JURY_SEVERITY_LABELS[highest] || "";
}

// 业务位置：【前端庭审】transcriptTextForMessage：围绕 房间消息和对话记录 计算本模块需要的派生信息，使其能够从 庭审轮次、双方陈述、法官 Agent 流 正确进入 下一轮提交或裁判草案审核入口。上游：庭审轮次、双方陈述、法官 Agent 流。下游：下一轮提交或裁判草案审核入口。边界：页面不得把 AI 建议显示为最终裁判。
function transcriptTextForMessage(message) {
  if (messageType(message) === "JURY_REVIEW_REPORT") {
    return displayHearingFactReferences(
      formatJuryReviewReport(
        juryReviewPayloadForMessage(message),
        rawMessageText(message),
      ),
    );
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
    return displayHearingFactReferences(compactEvidenceBootstrapReport(text));
  }
  return displayHearingFactReferences(stripTranscriptPreamble(text));
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

function fullReportSection(value) {
  return cleanPublicReportText(value)
    .replace(/^[\-—•·*\d.、\s]+/u, "")
    .replace(/[；;,\s]+$/u, "")
    .trim();
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
  const source = juryReviewSource(payload);
  if (!source) {
    return fullReportSection(
      stripTranscriptPreamble(
        displayRoomMessageText(sanitizeHearingCopy(fallbackText)),
      ),
    );
  }
  const publicMessage = fullReportSection(
    displayRoomMessageText(sanitizeHearingCopy(source.public_message || "")),
  );
  const findings = Array.isArray(source.findings) ? source.findings : [];
  const revisions = Array.isArray(source.mandatory_revisions)
    ? source.mandatory_revisions
    : [];
  if (findings.length || revisions.length) {
    const sections = [];
    if (publicMessage) sections.push(`评审结论\n${publicMessage}`);
    if (findings.length) {
      const findingLines = findings.map((item, index) => {
        const dimensionCode = String(item?.dimension || "").toUpperCase();
        const severityCode = String(item?.severity || "").toUpperCase();
        const heading = [
          JURY_DIMENSION_LABELS[dimensionCode] || dimensionCode || `发现 ${index + 1}`,
          JURY_SEVERITY_LABELS[severityCode] || severityCode,
        ].filter(Boolean).join(" · ");
        const assessment = fullReportSection(
          displayRoomMessageText(sanitizeHearingCopy(item?.assessment || "")),
        );
        const basis = Array.isArray(item?.basis)
          ? item.basis
              .map((value) => fullReportSection(
                displayRoomMessageText(sanitizeHearingCopy(value)),
              ))
              .filter(Boolean)
          : [];
        const details = [`${index + 1}. 【${heading}】${assessment}`];
        if (basis.length) details.push(`依据：${basis.join("；")}`);
        if (item?.requires_revision === true) details.push("结论：需要修订");
        return details.join("\n");
      });
      sections.push(`逐项评审（${findings.length} 项）\n${findingLines.join("\n\n")}`);
    }
    if (revisions.length) {
      const revisionLines = revisions
        .map((item, index) => {
          const text = fullReportSection(
            displayRoomMessageText(sanitizeHearingCopy(item)),
          );
          return text ? `${index + 1}. ${text}` : "";
        })
        .filter(Boolean);
      if (revisionLines.length) {
        sections.push(`强制修订（${revisionLines.length} 项）\n${revisionLines.join("\n")}`);
      }
    }
    return sections.join("\n\n");
  }

  const summary = fullReportSection(
    displayRoomMessageText(sanitizeHearingCopy(source.summary || publicMessage || "")),
  );
  const recommendations = Array.isArray(source.recommendations)
    ? source.recommendations
    : source.recommendation
      ? [source.recommendation]
      : [];
  const conciseRecommendations = recommendations
    .map((item) => fullReportSection(
      displayRoomMessageText(sanitizeHearingCopy(item)),
    ))
    .filter((item, index, values) => item && values.indexOf(item) === index)
    .slice(0, 3);
  const reviewNotes = fullReportSection(
    displayRoomMessageText(sanitizeHearingCopy(source.review_notes || "")),
  );
  const parts = [summary];
  if (conciseRecommendations.length) {
    parts.push(`复核建议：${conciseRecommendations.join("；")}`);
  }
  if (reviewNotes && !reportTextOverlaps(summary, reviewNotes)) {
    parts.push(`补充说明：${reviewNotes}`);
  }
  return parts.filter(Boolean).join("\n\n") || "AI 评审员已完成复核，报告已交由法官参考。";
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
      text: formatJuryReviewReport(
        juryReviewPayloadForMessage(message),
        rawMessageText(message),
      ),
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
  const loader = props.activeRunsLoader || loadActiveAgentRuns;
  const activeRuns = await loader(
    effectiveActor.value,
    caseId.value,
    "HEARING",
  );
  await Promise.all((activeRuns || []).map((descriptor) =>
    consumeHearingAgentRun(descriptor, hearingAgentPresentation(descriptor)),
  ));
}

function canConsumeHearingRunEvent(payload) {
  const descriptor = extractAgentRunDescriptor(payload);
  return Boolean(
    descriptor &&
      descriptor.streamAccess === "ACTOR_VISIBLE" &&
      descriptor.streamUrl,
  );
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
  const operation = String(descriptor.operation || "").toUpperCase();
  const runId = String(descriptor.runId || "").trim();
  if (!runId || !HEARING_FLOW_AGENT_OPERATIONS.has(operation)) return false;
  if (subscribedHearingRunIds.has(runId)) return false;
  subscribedHearingRunIds.add(runId);
  streamError.value = "";
  agentState.value = "STREAMING";
  const consumer = props.agentRunConsumer || consumeAgentRun;
  await consumer({
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
  const sourceMessageId = firstBoundText(
    [boundQuestionTranscriptMessage.value],
    ["id", "message_id", "messageId"],
  );
  const command = {
    schema_version: "hearing_answer_bundle.v4",
    question_set_id:
      questionSet.value.question_set_id || questionSet.value.questionSetId,
    question_set_hash:
      questionSet.value.question_set_hash || questionSet.value.questionSetHash,
    formal_issue_catalog_hash:
      questionSet.value.formal_issue_catalog_hash ||
      questionSet.value.formalIssueCatalogHash,
    answers: issueGuidanceItems.value.map((issue) => ({
      question_id: issue.questionId,
      issue_id: issue.issueId,
      answer_text: String(answerTexts[issue.questionId] || "").trim(),
    })),
    source_message_ids: sourceMessageId ? [sourceMessageId] : [],
  };

  try {
    const result = props.submitAnswersAction
      ? await props.submitAnswersAction(command)
      : await hearingApi.submitAnswers(actorSnapshot, caseId.value, command);
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
    for (const issue of issueGuidanceItems.value) {
      answerTexts[issue.questionId] = "";
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
          if (canConsumeHearingRunEvent(payload)) {
            void consumeHearingAgentRun(
              payload,
              hearingAgentPresentation(payload),
            ).catch(() => {});
          }
          void resumeActiveHearingRuns().catch(() => {});
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

watch(
  () => preludeAgentItems.value.map((item) => item.id).join("|"),
  () => startPreludeReplay(),
);

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
  finishPreludeReplay();
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
  const loaded = await load();
  if (loaded) startPreludeReplay();
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
  clearPreludeReplayTimer();
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
        :class="{
          'courtroom-center--without-input': !isCaseParty,
          'courtroom-center--answer-workbench':
            isCaseParty && flowStageCode === 'PARTY_ANSWERS_OPEN' && !currentActorSubmitted,
        }"
        :data-has-input-dock="isCaseParty"
      >
        <section
          class="hearing-stage-dock hearing-stage-dock--fixed-dashboard hearing-stage-dock--short"
          :class="`hearing-stage-dock--${stageDockMode}`"
          data-hearing-stage-dock
        >
          <header class="hearing-stage-dock__header">
            <div
              class="hearing-stage-dock__copy hearing-stage-dock__copy--stacked hearing-stage-dock__copy--breathing"
            >
              <span>当前阶段</span>
              <h2>{{ stageDockTitle }}</h2>
            </div>
            <div
              class="hearing-stage-dock__clock"
              data-hearing-stage-clock
            >
              <span>{{ stageDockMeta.label }}</span>
              <strong>{{ stageDockMeta.value }}</strong>
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
              :data-stage-number="item.number"
              :data-stage-progress-state="item.tone"
              :data-stage-connector-state="item.connectorTone"
            >
              <b :aria-label="`${item.label}：${item.status}`">
                <span class="stage-progress-board__marker-text" aria-hidden="true">
                  {{ item.tone === "complete" ? "✓" : item.number }}
                </span>
              </b>
              <div>
                <span class="stage-progress-board__label">{{ item.label }}</span>
                <em v-if="item.tone === 'active'" class="stage-progress-board__status">
                  {{ item.status }}
                </em>
              </div>
            </article>
          </div>

        </section>

        <section
          class="court-transcript"
          data-court-transcript
          :data-prelude-replay="preludeReplay.active ? 'active' : preludeReplay.complete ? 'complete' : 'idle'"
        >
          <div
            ref="courtTranscriptRail"
            class="court-transcript__messages"
            data-transcript-scroll-rail="true"
          >
            <template v-for="item in presentedCourtTranscriptItems" :key="item.id">
              <div
                v-if="item.type === 'system'"
                class="court-system-notice"
                :class="{
                  'court-system-notice--prelude-reveal': item.presentationPrelude,
                }"
                data-court-system-notice
                :data-court-message-id="item.id"
                :data-prelude-state="item.preludeState || undefined"
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
                item.presentationPrelude || item.presentationStreaming ? 'court-message--prelude-reveal' : '',
                ]"
                :data-court-message="item.type"
                :data-court-message-id="item.id"
                :data-long-transcript="isLongTranscript(item)"
                :data-streaming="item.isStreaming || item.presentationStreaming ? 'true' : undefined"
                :data-prelude-state="item.preludeState || undefined"
                :data-agent-run-id="item.runId || undefined"
                :data-run-message-count="item.messageCount > 1 ? item.messageCount : undefined"
                :data-agent-stream-card="item.isStreaming ? item.streamCardKey : undefined"
                :data-agent-stream-status="item.isStreaming ? item.streamStatus : undefined"
                :data-agent-streaming-message="item.isStreaming ? 'true' : undefined"
                :aria-live="item.isStreaming || item.presentationStreaming ? 'polite' : undefined"
                :aria-busy="item.isStreaming ? item.streamActive : item.presentationStreaming || undefined"
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
                  <span
                    v-if="item.type === 'jury' && (item.isStreaming || item.riskLevel || item.confidenceScore || item.juryHighestSeverity || item.juryFindingCount || item.juryRevisionCount)"
                    class="court-message__jury-tags"
                    aria-label="评审辅助指标"
                  >
                    <template v-if="item.isStreaming">
                      <span>评审报告</span>
                      <em>生成中</em>
                    </template>
                    <template v-else>
                      <span v-if="item.juryHighestSeverity">最高问题等级</span>
                      <em v-if="item.juryHighestSeverity">{{ item.juryHighestSeverity }}</em>
                      <span v-if="item.juryFindingCount">{{ item.juryFindingCount }} 项发现</span>
                      <span v-if="item.juryRevisionCount">{{ item.juryRevisionCount }} 项必改</span>
                      <span v-if="item.riskLevel">风险等级</span>
                      <em v-if="item.riskLevel">{{ item.riskLevel }}</em>
                      <span v-if="item.confidenceScore">可信分</span>
                      <em v-if="item.confidenceScore">{{ item.confidenceScore }}</em>
                    </template>
                  </span>
                </strong>
                <span :class="{ 'court-message__stream-status': item.isStreaming || item.presentationStreaming }">
                  {{ item.presentationStreaming ? "正在宣读" : item.time }}
                </span>
              </header>
              <p>
                <span v-if="item.text">{{ visibleTranscriptText(item) }}</span>
                <span v-else class="court-message__stream-waiting">正在组织内容</span>
                <i
                  v-if="item.streamActive || item.presentationStreaming"
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
              v-if="partyAnswersTranscriptSyncError"
              class="court-transcript__sync-warning"
              data-hearing-transcript-sync-error
              role="alert"
            >
              <strong>庭审记录同步异常</strong>
              <small>{{ partyAnswersTranscriptSyncMessage }}</small>
            </div>

            <div
              v-if="loadingState.messages"
              class="court-transcript__empty court-transcript__empty--loading"
              data-court-transcript-loading
            >
              <strong>庭审记录加载中</strong>
              <small>正在读取开庭消息和双方陈述，请稍候。</small>
            </div>
            <div
              v-else-if="!courtTranscriptItems.length && !partyAnswersTranscriptSyncError"
              class="court-transcript__empty"
              data-court-transcript-empty
            >
              <strong>{{ emptyTranscriptCopy.title }}</strong>
              <small>{{ emptyTranscriptCopy.body }}</small>
            </div>
          </div>
        </section>

        <section
          v-if="isCaseParty"
          class="stage-input-bar stage-input-bar--fixed-dock"
          :class="{
            'stage-input-bar--answer-workbench':
              flowStageCode === 'PARTY_ANSWERS_OPEN' && !currentActorSubmitted,
          }"
          data-stage-input-bar
        >
          <div
            class="stage-input-bar__body"
            :class="{
              'stage-input-bar__body--with-header':
                isPartyInputStage(flowStageCode) && flowStageCode !== 'PARTY_ANSWERS_OPEN',
            }"
          >
            <header
              v-if="isPartyInputStage(flowStageCode) && flowStageCode !== 'PARTY_ANSWERS_OPEN'"
              class="stage-input-bar__header"
              data-stage-input-header
            >
              <div
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
              :data-current-party-role="role"
              @submit.prevent="submitPartyStatement"
            >
              <div
                v-if="activeAnswerIssue"
                class="hearing-answer-workbench"
                data-hearing-answer-workbench
              >
                <section
                  class="hearing-answer-question-card"
                  data-hearing-issue-guidance
                  data-hearing-issue
                >
                  <header>
                    <span>争议焦点</span>
                    <strong>{{ activeAnswerIssueIndex + 1 }} / {{ issueGuidanceItems.length }}</strong>
                  </header>
                  <div class="hearing-answer-question-card__scroll" data-hearing-question-scroll>
                    <h3>{{ activeAnswerIssue.statement }}</h3>
                    <p v-if="activeAnswerIssue.prompt" data-hearing-party-prompt>
                      {{ activeAnswerIssue.prompt }}
                    </p>
                  </div>
                </section>

                <section class="hearing-answer-editor">
                  <header>
                    <div>
                      <strong>{{ role === "MERCHANT" ? "商家回答" : "用户回答" }}</strong>
                      <small>{{ completedAnswerCount }} / {{ issueGuidanceItems.length }} 已完成</small>
                    </div>
                    <nav class="hearing-answer-switcher" aria-label="切换争议焦点">
                      <button
                        type="button"
                        :disabled="!hasPreviousAnswerIssue"
                        aria-label="切换到上一个争议焦点"
                        data-hearing-previous-issue
                        @click="showPreviousAnswerIssue"
                      >
                        <span aria-hidden="true">&larr;</span>
                      </button>
                      <span>{{ activeAnswerIssueIndex + 1 }} / {{ issueGuidanceItems.length }}</span>
                      <button
                        type="button"
                        :disabled="!hasNextAnswerIssue"
                        aria-label="切换到下一个争议焦点"
                        data-hearing-next-issue
                        @click="showNextAnswerIssue"
                      >
                        <span aria-hidden="true">&rarr;</span>
                      </button>
                    </nav>
                  </header>

                  <label class="hearing-answer-editor__field">
                    <textarea
                      ref="activeAnswerTextarea"
                      v-model="answerTexts[issueAnswerKey(activeAnswerIssue)]"
                      :disabled="submittingAnswers"
                      rows="4"
                      maxlength="2000"
                      :aria-label="`焦点 ${activeAnswerIssueIndex + 1} 的本方回答`"
                      :data-hearing-answer-question-id="activeAnswerIssue.questionId"
                      data-hearing-answer
                      placeholder="请直接回答本争议点；如立场未变，也请明确写明沿用原立场及理由。"
                    ></textarea>
                  </label>

                  <footer>
                    <p
                      v-if="issueGuidanceItems.length && !canSubmitAnswers"
                      class="hearing-issue-guidance__sync-note"
                      data-hearing-answer-sync-note
                    >
                      问题集正在完成正式绑定；可先填写，绑定完成后即可一次提交。
                    </p>
                    <p v-else>提交后本方输入关闭，待另一方提交后统一公开。</p>
                    <button
                      type="button"
                      class="stage-input-bar__submit"
                      data-submit-answer-bundle
                      data-submit-party-statement
                      :disabled="submittingAnswers || !statementComplete || !canSubmitAnswers"
                      @click="submitPartyStatement()"
                    >
                      {{ submittingAnswers ? "正在提交…" : "提交本方回答" }}
                    </button>
                  </footer>
                </section>
              </div>

              <div
                v-else
                class="hearing-answer-workbench__empty"
                data-hearing-party-prompt-empty
              >
                <strong>当前没有本方定向问题</strong>
                <small>问题集完成绑定后，这里会显示可回答的争议焦点。</small>
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
                <strong>{{ allPartiesStageTerminal ? "双方回答已提交" : "本方回答已提交" }}</strong>
                <small v-if="allPartiesStageTerminal">系统正在统一公开双方陈述并整理本轮案情。</small>
                <small v-else>输入已关闭，请等待对方提交；双方到齐后系统会统一公开两方陈述并推进。</small>
              </div>
            </div>
            <div
              v-else
              class="stage-input-bar__sealed-status stage-input-bar__sealed-status--locked"
              data-stage-input-locked
              role="status"
            >
              <span
                class="stage-input-bar__lock-mark"
                data-stage-lock-icon
                aria-hidden="true"
              >&#x1F512;&#xFE0E;</span>
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

<style scoped src="./HearingCourtView.css"></style>
