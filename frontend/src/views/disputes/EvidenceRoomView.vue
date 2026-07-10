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
import { newIdempotencyKey } from "../../api/client";
import { evidenceApi } from "../../api/evidence";
import { roomApi } from "../../api/rooms";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import ConversationStream from "../../components/room/ConversationStream.vue";
import PhaseCountdown from "../../components/room/PhaseCountdown.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
  createRoomState,
  streamRoomEvents,
} from "../../stores/room";

const props = defineProps({
  initialCatalog: { type: Object, default: null },
  initialCompletion: { type: Object, default: null },
  initialMessages: { type: Array, default: null },
  viewerRole: { type: String, default: "" },
  deadlineAt: { type: String, default: "" },
  serverNow: { type: String, default: "" },
  completeAction: { type: Function, default: null },
  eventStreamer: { type: Function, default: null },
  messageAction: { type: Function, default: null },
  agentReplyPollAttempts: { type: Number, default: 5 },
  agentReplyPollDelayMs: { type: Number, default: 1500 },
});

const route = useRoute();
const router = useRouter();
const catalog = ref(props.initialCatalog);
const completion = ref(props.initialCompletion);
const uploading = ref(false);
const submittingBatch = ref(false);
const completing = ref(false);
const error = ref("");
const evidenceGateError = ref("");
const agentState = ref("LISTENING");
const fileInput = ref(null);
const messages = ref([...(props.initialMessages || [])]);
const selectedEvidence = ref(null);
const expandedEvidenceGroup = ref(null);
const deletingEvidenceIds = ref(new Set());
const eventState = reactive(createRoomState());
let eventAbortController = new AbortController();
let eventStreamActive = false;
let workspaceGeneration = 0;

const caseId = computed(
  () => catalog.value?.case_id || route.params.caseId,
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
const isMerchant = computed(() => role.value === "MERCHANT");
const shouldEnsureOpening = computed(() => ["USER", "MERCHANT"].includes(role.value));
const displayedMessages = computed(() => {
  const hasDossierSpecificOpening = messages.value.some((message) =>
    isDossierSpecificOpeningMessage(message),
  );
  if (!hasDossierSpecificOpening) return messages.value;
  return messages.value.filter((message) => !isSupersededOpeningMessage(message));
});
const items = computed(() => catalog.value?.items || []);
const actorOwnedItems = computed(() =>
  items.value.filter(
    (item) =>
      item.submitted_by_role === role.value,
  ),
);
const pendingItems = computed(() =>
  actorOwnedItems.value.filter(
    (item) => evidenceSubmissionStatus(item) === "PENDING_SUBMISSION",
  ),
);
const submittedItems = computed(() =>
  actorOwnedItems.value.filter(
    (item) => evidenceSubmissionStatus(item) === "SUBMITTED",
  ),
);
const initiatorRole = computed(
  () => catalog.value?.initiator_role || catalog.value?.initiatorRole || "USER",
);
const currentActorIsInitiator = computed(() => role.value === initiatorRole.value);
const canCompleteEvidenceLocally = computed(
  () => !currentActorIsInitiator.value || submittedItems.value.length > 0,
);
const effectiveDeadline = computed(
  () =>
    props.deadlineAt ||
    completion.value?.deadline_at ||
    new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString(),
);
const effectiveServerNow = computed(
  () => props.serverNow || new Date().toISOString(),
);
const currentPartyCompleted = computed(() => {
  if (completion.value?.sealed) return true;
  if (role.value === "USER") return Boolean(completion.value?.user_completed);
  if (role.value === "MERCHANT") return Boolean(completion.value?.merchant_completed);
  return false;
});
const waitingCounterpartyLabel = computed(() =>
  isMerchant.value ? "用户" : "商家",
);
const completionStatusMessage = computed(() => {
  if (completion.value?.sealed) {
    return "卷宗已封存，庭审入口已开放。";
  }
  if (currentPartyCompleted.value) {
    return `你的举证已完成，正在等待${waitingCounterpartyLabel.value}完成或举证时效结束。`;
  }
  return "提交完成只代表当前一方完成；系统会等待另一方或举证时效结束。";
});
const connectionState = computed(() => {
  if (eventState.connected) return "connected";
  if (eventState.reconnecting) return "reconnecting";
  return "offline";
});
const evidenceSourceType = computed(() => {
  if (role.value === "MERCHANT") return "MERCHANT_UPLOAD";
  if (role.value === "USER") return "USER_UPLOAD";
  return "PLATFORM_UPLOAD";
});

const statusLabels = {
  PENDING: "待核验",
  VERIFIED: "已核验",
  PLAUSIBLE: "基本可信",
  SUSPICIOUS: "存在疑点",
  REJECTED: "不予采纳",
  NEEDS_HUMAN_REVIEW: "待人工复核",
};

const evidenceTypeLabels = {
  CHAT_SCREENSHOT: "沟通截图",
  LOGISTICS_PROOF: "物流凭证",
  DELIVERY_RECORD: "履约记录",
  VIDEO: "视频证据",
  IMAGE: "图片证据",
  DOCUMENT: "文档材料",
  OTHER: "其他材料",
};

const confidenceLabels = {
  HIGH: "高置信",
  MEDIUM: "中置信",
  LOW: "低置信",
  UNKNOWN: "待评分",
};

const fileIconCatalog = {
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

function evidenceField(item, snakeCaseKey, camelCaseKey, fallback = "") {
  return item?.[snakeCaseKey] ?? item?.[camelCaseKey] ?? fallback;
}

function evidenceId(item) {
  return evidenceField(item, "evidence_id", "evidenceId", "");
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
  return status || "待确认";
}

function isDeletingEvidence(item) {
  return deletingEvidenceIds.value.has(evidenceId(item));
}

function evidenceConfidenceScore(item) {
  const raw = evidenceField(item, "confidence_score", "confidenceScore", null);
  if (raw === null || raw === undefined || raw === "") return null;
  const numeric = Number(raw);
  if (!Number.isFinite(numeric)) return null;
  return numeric <= 1 ? Math.round(numeric * 100) : Math.round(numeric);
}

function evidenceConfidenceLevel(item) {
  const value = String(
    evidenceField(item, "confidence_level", "confidenceLevel", "UNKNOWN"),
  ).toUpperCase();
  return confidenceLabels[value] || confidenceLabels.UNKNOWN;
}

function evidenceConfidenceTone(item) {
  const value = String(
    evidenceField(item, "confidence_level", "confidenceLevel", "UNKNOWN"),
  ).toLowerCase();
  if (["high", "medium", "low"].includes(value)) return value;
  const score = evidenceConfidenceScore(item);
  if (score === null) return "unknown";
  if (score >= 80) return "high";
  if (score >= 50) return "medium";
  return "low";
}

function evidenceConfidenceCopy(item) {
  const score = evidenceConfidenceScore(item);
  const label = evidenceConfidenceLevel(item);
  return score === null ? label : `${score}% · ${label}`;
}

function evidenceOwnerLabel(item) {
  const owner = evidenceField(item, "submitted_by_role", "submittedByRole", "");
  if (owner === "MERCHANT") return "商家提交";
  if (owner === "USER") return "用户提交";
  if (owner === "PLATFORM_REVIEWER") return "平台提交";
  return owner || "来源待确认";
}

function evidenceFeedback(item) {
  return evidenceField(item, "verification_feedback", "verificationFeedback", "");
}

function evidenceParsedText(item) {
  return evidenceField(item, "parsed_text", "parsedText", "");
}

function evidenceOriginalFilename(item) {
  return evidenceField(item, "original_filename", "originalFilename", "");
}

function evidenceFileSourceName(item) {
  return evidenceOriginalFilename(item) || evidenceField(item, "content_url", "contentUrl", "");
}

function fileExtension(value) {
  const cleanValue = String(value || "").split(/[?#]/)[0];
  const fileName = cleanValue.split(/[\\/]/).pop() || "";
  const lastDotIndex = fileName.lastIndexOf(".");
  if (lastDotIndex <= 0 || lastDotIndex === fileName.length - 1) return "";
  return fileName.slice(lastDotIndex + 1).toLowerCase();
}

function evidenceFileIcon(item) {
  const extension = fileExtension(evidenceFileSourceName(item));
  const evidenceType = String(evidenceField(item, "evidence_type", "evidenceType", "")).toUpperCase();
  if (extension === "pdf") return fileIconCatalog.pdf;
  if (wordExtensions.has(extension)) return fileIconCatalog.word;
  if (markdownExtensions.has(extension)) return fileIconCatalog.markdown;
  if (textExtensions.has(extension)) return fileIconCatalog.text;
  if (imageExtensions.has(extension) || ["IMAGE", "CHAT_SCREENSHOT"].includes(evidenceType)) {
    return fileIconCatalog.image;
  }
  if (videoExtensions.has(extension) || evidenceType === "VIDEO") return fileIconCatalog.video;
  if (["DOCUMENT", "DELIVERY_RECORD", "LOGISTICS_PROOF"].includes(evidenceType)) {
    return fileIconCatalog.document;
  }
  return fileIconCatalog.other;
}

function evidenceFileIconStatusClass(item) {
  return evidenceSubmissionStatus(item) === "PENDING_SUBMISSION"
    ? "evidence-file-icon--pending"
    : "evidence-file-icon--submitted";
}

function openEvidenceDetail(item) {
  selectedEvidence.value = item;
}

function closeEvidenceDetail() {
  selectedEvidence.value = null;
}

function openEvidenceGroup(group) {
  expandedEvidenceGroup.value = group;
}

function closeEvidenceGroup() {
  expandedEvidenceGroup.value = null;
}

const expandedEvidenceItems = computed(() => {
  if (expandedEvidenceGroup.value === "pending") return pendingItems.value;
  if (expandedEvidenceGroup.value === "submitted") return submittedItems.value;
  return [];
});

const expandedEvidenceTitle = computed(() =>
  expandedEvidenceGroup.value === "pending" ? "本批待提交" : "我的原件匣",
);

async function load() {
  const generation = workspaceGeneration;
  const actorSnapshot = { ...effectiveActor.value };
  const caseSnapshot = caseId.value;
  try {
    if (catalog.value === null) {
      const nextCatalog = await loadCatalogOrEmpty(actorSnapshot, caseSnapshot);
      if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
      catalog.value = nextCatalog;
    }
    if (completion.value === null) {
      const nextCompletion = await evidenceApi.completion(actorSnapshot, caseSnapshot);
      if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
      completion.value = nextCompletion;
    }
    if (props.initialMessages === null) {
      const nextMessages = await loadActorScopedMessages(
        generation,
        actorSnapshot,
        caseSnapshot,
        { ensureOpening: shouldEnsureOpening.value },
      );
      if (nextMessages === null) return;
      messages.value = nextMessages;
    }
  } catch (failure) {
    if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

function isCurrentWorkspace(generation, actorSnapshot, caseSnapshot) {
  return (
    generation === workspaceGeneration &&
    caseSnapshot === caseId.value &&
    actorSnapshot.id === effectiveActor.value.id &&
    actorSnapshot.role === effectiveActor.value.role
  );
}

async function loadActorScopedMessages(
  generation,
  actorSnapshot,
  caseSnapshot,
  options = {},
) {
  const firstMessages = await roomApi.messages(
    actorSnapshot,
    caseSnapshot,
    "EVIDENCE",
  );
  if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) {
    return null;
  }
  if (!shouldRequestEvidenceOpening(options.ensureOpening, firstMessages)) {
    return firstMessages;
  }
  await roomApi.ensureOpening(actorSnapshot, caseSnapshot, "EVIDENCE");
  if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) {
    return null;
  }
  const openedMessages = await roomApi.messages(
    actorSnapshot,
    caseSnapshot,
    "EVIDENCE",
  );
  if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) {
    return null;
  }
  return openedMessages;
}

function shouldRequestEvidenceOpening(ensureOpening, firstMessages) {
  if (!ensureOpening) return false;
  return (
    firstMessages.length === 0 ||
    containsOnlySupersededOpeningMessages(firstMessages)
  );
}

function containsOnlySupersededOpeningMessages(firstMessages) {
  return (
    firstMessages.length > 0 &&
    firstMessages.every((message) => isSupersededOpeningMessage(message))
  );
}

function isSupersededOpeningMessage(message) {
  const text = message?.message_text || "";
  return (
    message?.sender_role === "CUSTOMER_SERVICE" &&
    message?.message_type === "AGENT_MESSAGE" &&
    (text.includes("您好！我是您的证据书记官") ||
      text.includes("请上传与本案相关的证据材料") ||
      text.includes("争议焦点待确认") ||
      text.includes("原始证据文件、证据形成时间、证据来源路径"))
  );
}

function isDossierSpecificOpeningMessage(message) {
  const text = message?.message_text || "";
  return (
    message?.sender_role === "CUSTOMER_SERVICE" &&
    message?.message_type === "AGENT_MESSAGE" &&
    text.includes("接待室收敛的案情") &&
    !isSupersededOpeningMessage(message)
  );
}

async function loadCatalogOrEmpty(actorSnapshot, caseSnapshot) {
  try {
    return await evidenceApi.catalog(actorSnapshot, caseSnapshot);
  } catch (failure) {
    if (isMissingEvidenceCatalog(failure)) {
      return { case_id: caseSnapshot, items: [] };
    }
    throw failure;
  }
}

function isMissingEvidenceCatalog(failure) {
  return ["EVIDENCE_NOT_FOUND", "RESOURCE_NOT_FOUND"].includes(failure?.code);
}

async function refreshWorkspace(options = {}) {
  const generation = options.generation ?? workspaceGeneration;
  const actorSnapshot = { ...effectiveActor.value };
  const caseSnapshot = caseId.value;
  const [nextCatalog, nextCompletion, nextMessages] = await Promise.all([
    loadCatalogOrEmpty(actorSnapshot, caseSnapshot),
    evidenceApi.completion(actorSnapshot, caseSnapshot),
    loadActorScopedMessages(generation, actorSnapshot, caseSnapshot, {
      ensureOpening: options.ensureOpening && shouldEnsureOpening.value,
    }),
  ]);
  if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) {
    return false;
  }
  if (nextMessages === null) {
    return false;
  }
  catalog.value = nextCatalog;
  completion.value = nextCompletion;
  messages.value = nextMessages;
  return true;
}

function nextLocalSequence() {
  return Math.max(0, ...messages.value.map((message) => message.sequence_no || 0)) + 1;
}

function upsertMessage(message) {
  const existingIndex = messages.value.findIndex((item) => item.id === message.id);
  if (existingIndex >= 0) {
    messages.value.splice(existingIndex, 1, message);
    return;
  }
  messages.value.push(message);
}

function removeMessage(messageId) {
  messages.value = messages.value.filter((message) => message.id !== messageId);
}

function hasAgentReplyAfter(sequenceNo) {
  return messages.value.some(
    (message) =>
      message.message_type === "AGENT_MESSAGE" &&
      (message.sequence_no || 0) > sequenceNo,
  );
}

function delay(ms) {
  if (ms <= 0) return Promise.resolve();
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

async function refreshUntilAgentReply(afterSequenceNo) {
  await refreshWorkspace();
  if (hasAgentReplyAfter(afterSequenceNo)) return;
  for (let attempt = 0; attempt < props.agentReplyPollAttempts; attempt += 1) {
    await delay(props.agentReplyPollDelayMs);
    await refreshWorkspace();
    if (hasAgentReplyAfter(afterSequenceNo)) return;
  }
}

async function postMessage(command) {
  error.value = "";
  agentState.value = "THINKING";
  const pendingId = `LOCAL_PENDING_${Date.now()}`;
  const pendingMessage = {
    id: pendingId,
    case_id: caseId.value,
    sequence_no: nextLocalSequence(),
    sender_role: role.value,
    message_type: command.message_type,
    message_text: command.text,
    attachment_refs: command.attachment_refs || [],
    pending: true,
  };
  messages.value.push(pendingMessage);
  try {
    const saved = props.messageAction
      ? await props.messageAction(command)
      : await roomApi.postMessage(
          effectiveActor.value,
          caseId.value,
          "EVIDENCE",
          command,
        );
    if (saved) {
      removeMessage(pendingId);
      upsertMessage(saved);
    }
    if (!props.messageAction) {
      await refreshUntilAgentReply(saved?.sequence_no || pendingMessage.sequence_no);
    }
    agentState.value = "SPEAKING";
  } catch (failure) {
    removeMessage(pendingId);
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

function startEventStream() {
  if (eventStreamActive) {
    eventAbortController.abort();
    eventAbortController = new AbortController();
  }
  eventStreamActive = true;
  const streamer = props.eventStreamer || streamRoomEvents;
  const generation = workspaceGeneration;
  const actorSnapshot = { ...effectiveActor.value };
  const caseSnapshot = caseId.value;
  void streamer({
    actor: actorSnapshot,
    caseId: caseSnapshot,
    roomType: "EVIDENCE",
    state: eventState,
    signal: eventAbortController.signal,
    snapshotLoader: () => refreshWorkspace({ generation }),
    applyEvent: async (event) => {
      if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
      if (event.event === "HEARING_OPENED") {
        completion.value = {
          ...(completion.value || {}),
          sealed: true,
          next_room: "HEARING",
        };
        agentState.value = "HANDOFF";
      }
    },
  });
}

async function uploadFiles(event) {
  const files = [...(event.target.files || [])];
  if (!files.length) return;
  uploading.value = true;
  error.value = "";
  agentState.value = "THINKING";
  try {
    for (const file of files) {
      await evidenceApi.upload(effectiveActor.value, caseId.value, {
        file,
        evidenceType: file.type.startsWith("video/")
          ? "VIDEO"
          : "OTHER",
        sourceType: evidenceSourceType.value,
        visibility: "PRIVATE",
      });
    }
    await refreshWorkspace();
    agentState.value = "LISTENING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    uploading.value = false;
    if (fileInput.value) fileInput.value.value = "";
  }
}

async function submitPendingBatch() {
  if (!pendingItems.value.length || submittingBatch.value) return;
  submittingBatch.value = true;
  error.value = "";
  agentState.value = "THINKING";
  const evidenceIds = pendingItems.value.map((item) => evidenceId(item));
  try {
    const result = await evidenceApi.submitBatch(
      effectiveActor.value,
      caseId.value,
      {
        evidence_ids: evidenceIds,
        batch_note: "",
      },
      newIdempotencyKey("evidence-batch"),
    );
    if (result?.room_message) {
      upsertMessage(result.room_message);
    }
    await refreshUntilAgentReply(
      result?.room_message?.sequence_no || nextLocalSequence() - 1,
    );
    agentState.value = "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    submittingBatch.value = false;
  }
}

async function deletePendingEvidence(item) {
  const id = evidenceId(item);
  if (!id || deletingEvidenceIds.value.has(id)) return;
  deletingEvidenceIds.value = new Set([...deletingEvidenceIds.value, id]);
  error.value = "";
  try {
    await evidenceApi.deletePending(effectiveActor.value, caseId.value, id);
    await refreshWorkspace();
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    const next = new Set(deletingEvidenceIds.value);
    next.delete(id);
    deletingEvidenceIds.value = next;
  }
}

async function completeEvidence() {
  if (!canCompleteEvidenceLocally.value) {
    evidenceGateError.value = "发起争议方需先正式提交至少 1 份相关证据，当前证据栏为空，暂不能进入下一步。";
    agentState.value = "ERROR";
    return;
  }
  completing.value = true;
  error.value = "";
  agentState.value = "THINKING";
  try {
    const result = props.completeAction
      ? await props.completeAction()
      : await evidenceApi.complete(effectiveActor.value, caseId.value);
    completion.value = mergeCompletionResult(completion.value, result);
    agentState.value = completion.value.sealed ? "HANDOFF" : "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    completing.value = false;
  }
}

function mergeCompletionResult(previous, result) {
  const sealed = result.sealed ?? result.all_parties_completed ?? false;
  const completedRole = result.completed_role || result.completedRole;
  const next = {
    ...(previous || {}),
    ...result,
    sealed,
  };
  if (completedRole === "USER") {
    next.user_completed = true;
  }
  if (completedRole === "MERCHANT") {
    next.merchant_completed = true;
  }
  if (sealed) {
    next.user_completed = true;
    next.merchant_completed = true;
  }
  return next;
}

function dismissEvidenceGate() {
  evidenceGateError.value = "";
  agentState.value = "LISTENING";
}

function enterHearing() {
  return router.push(`/disputes/${caseId.value}/hearing`);
}

onMounted(async () => {
  await load();
  if (
    props.eventStreamer ||
    props.initialCatalog === null ||
    props.initialCompletion === null
  ) {
    startEventStream();
  }
});
watch(role, async (nextRole, previousRole) => {
  if (!previousRole || nextRole === previousRole) return;
  workspaceGeneration += 1;
  const generation = workspaceGeneration;
  error.value = "";
  agentState.value = "THINKING";
  catalog.value = null;
  completion.value = null;
  selectedEvidence.value = null;
  messages.value = [];
  if (eventStreamActive) {
    startEventStream();
  }
  try {
    await refreshWorkspace({ generation, ensureOpening: props.initialMessages === null });
    agentState.value = "LISTENING";
  } catch (failure) {
    if (generation !== workspaceGeneration) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  }
});
onBeforeUnmount(() => eventAbortController.abort());
</script>

<template>
  <RoomShell
    eyebrow="EVIDENCE GARDEN"
    title="证据书记官室"
    :case-id="caseId"
    :connection-state="connectionState"
  >
    <template #clock>
      <div data-evidence-countdown>
        <PhaseCountdown
          label="举证窗口"
          :deadline-at="effectiveDeadline"
          :server-now="effectiveServerNow"
        />
      </div>
    </template>

    <template #agent>
      <DigitalHuman
        :state="agentState"
        name="小册"
        role="证据书记官"
        :message="
          isMerchant
            ? '商家证据方你好。我会围绕案情核验你方原件的来源、完整性和关联性；本房间只处理单方提交。'
            : '用户证据方你好。图片、视频、文档都可以先放入待提交区；确认后我只读取你提交的这一批。'
        "
      />
    </template>

    <div class="evidence-room" data-evidence-room-layout>
      <section class="evidence-room__conversation" data-evidence-chat-panel>
        <div class="evidence-room__case-note">
          <span>书记官正在核对</span>
          <h2>围绕接待室收敛的案情补充证据</h2>
          <p>
            发起争议方须至少正式提交 1 份相关证据；另一方可提交材料，或等待举证时效结束。
          </p>
        </div>
        <div class="evidence-room__conversation-frame">
          <ConversationStream
            :messages="displayedMessages"
            :disabled="uploading || submittingBatch || completing"
            agent-label="证据书记官"
            placeholder="告诉书记官这份证据从哪里来、形成于何时、能证明什么…"
            @submit="postMessage"
          />
        </div>
      </section>

      <section class="evidence-board" data-evidence-board-panel>
        <header class="evidence-board__header">
          <div>
            <span class="evidence-kicker">EVIDENCE BOARD</span>
            <h2>已提交证据</h2>
            <p>点击任意证据卡片查看解析文本、核验反馈和置信度。</p>
          </div>
          <span class="evidence-board__badge">
            {{ isMerchant ? "商家证据方" : "用户证据方" }}
          </span>
        </header>

        <section class="evidence-uploader">
          <div class="evidence-uploader__illustration" aria-hidden="true">
            <span>📎</span><span>🖼️</span><span>🎞️</span>
          </div>
          <div>
            <span class="evidence-kicker">MULTIMODAL DESK</span>
            <strong>上传图片、视频、Markdown 或文档</strong>
            <small>OCR/解析后会进入证据库，并由书记官给出核验建议。</small>
          </div>
          <label class="evidence-uploader__button">
            {{ uploading ? "核验中…" : "选择材料" }}
            <input
              ref="fileInput"
              type="file"
              multiple
              :disabled="uploading"
              @change="uploadFiles"
            />
          </label>
        </section>

        <div class="evidence-board__list" data-evidence-list-scroll>
          <section class="evidence-library evidence-library--pending" data-evidence-pending>
            <header>
              <div>
                <span class="evidence-kicker">STAGING</span>
                <h3>本批待提交</h3>
              </div>
              <span class="privacy-seal">{{ pendingItems.length }} 个待提交</span>
            </header>
            <p v-if="!pendingItems.length" class="evidence-empty">选择材料后，会先进入这里；提交前可以删除。</p>
            <article
              v-for="item in pendingItems"
              :key="evidenceId(item)"
              class="evidence-card evidence-card--pending"
              data-evidence-card
              @click="openEvidenceDetail(item)"
            >
              <button
                type="button"
                class="evidence-card__remove"
                data-delete-pending-evidence
                :disabled="isDeletingEvidence(item)"
                @click.stop="deletePendingEvidence(item)"
              >
                ×
              </button>
              <span
                class="evidence-card__icon evidence-file-icon"
                :class="evidenceFileIconStatusClass(item)"
                :data-file-kind="evidenceFileIcon(item).kind"
                :aria-label="evidenceFileIcon(item).label"
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
              <span class="evidence-card__main">
                <strong>{{ evidenceTypeLabels[item.evidence_type] || item.evidence_type }}</strong>
                <small
                  class="evidence-card__filename"
                  data-evidence-filename
                  :title="evidenceOriginalFilename(item) || evidenceId(item)"
                >
                  {{ evidenceOriginalFilename(item) || evidenceId(item) }}
                </small>
              </span>
              <span class="evidence-card__meta" data-evidence-status-row>
                <em class="evidence-card__status">
                  {{ evidenceSubmissionStatusLabel(item) }}
                </em>
                <span class="verification-pill" data-verification="PENDING">待提交</span>
                <span class="confidence-pill" data-confidence="unknown">书记官尚未核验</span>
              </span>
              <span
                v-if="evidenceFeedback(item)"
                class="evidence-card__description"
                data-evidence-description
              >
                {{ evidenceFeedback(item) }}
              </span>
            </article>
            <div v-if="pendingItems.length" class="evidence-library__actions">
              <button type="button" data-expand-pending-evidence @click="openEvidenceGroup('pending')">
                展开
              </button>
              <button
                type="button"
                data-submit-evidence-batch
                :disabled="submittingBatch"
                @click="submitPendingBatch"
              >
                {{ submittingBatch ? "提交中…" : "提交本批给书记官" }}
              </button>
            </div>
          </section>

          <section class="evidence-library evidence-library--private" data-evidence-originals>
            <header>
              <div>
                <span class="evidence-kicker">MY ORIGINALS</span>
                <h3>我的原件匣</h3>
              </div>
              <span class="privacy-seal">仅当前一方可见</span>
            </header>
            <p v-if="!submittedItems.length" class="evidence-empty">正式提交后，材料会锁定进入我的原件匣。</p>
            <button
              v-for="item in submittedItems"
              :key="evidenceId(item)"
              type="button"
              class="evidence-card"
              data-evidence-card
              @click="openEvidenceDetail(item)"
            >
              <span
                class="evidence-card__icon evidence-file-icon"
                :class="evidenceFileIconStatusClass(item)"
                :data-file-kind="evidenceFileIcon(item).kind"
                :aria-label="evidenceFileIcon(item).label"
              >
                <span class="evidence-file-icon__body" aria-hidden="true">
                  <span class="evidence-file-icon__landscape"></span>
                  <span class="evidence-file-icon__play"></span>
                  <span class="evidence-file-icon__lines"></span>
                </span>
                <span class="evidence-file-icon__badge" data-file-badge>
                  {{ evidenceFileIcon(item).badge }}
                </span>
                <span class="evidence-file-icon__lock" aria-hidden="true"></span>
              </span>
              <span class="evidence-card__main">
                <strong>{{ evidenceTypeLabels[item.evidence_type] || item.evidence_type }}</strong>
                <small
                  class="evidence-card__filename"
                  data-evidence-filename
                  :title="evidenceOriginalFilename(item) || evidenceId(item)"
                >
                  {{ evidenceOriginalFilename(item) || evidenceId(item) }}
                </small>
              </span>
              <span class="evidence-card__meta" data-evidence-status-row>
                <em class="evidence-card__status">
                  {{ evidenceSubmissionStatusLabel(item) }}
                </em>
                <em class="evidence-card__status">{{ evidenceOwnerLabel(item) }}</em>
                <span
                  class="verification-pill"
                  :data-verification="item.verification_status || 'PENDING'"
                >
                  {{ statusLabels[item.verification_status] || item.verification_status || "待核验" }}
                </span>
                <span
                  class="confidence-pill"
                  :data-confidence="evidenceConfidenceTone(item)"
                  data-evidence-confidence
                >
                  {{ evidenceConfidenceCopy(item) }}
                </span>
              </span>
              <span
                v-if="evidenceFeedback(item)"
                class="evidence-card__description"
                data-evidence-description
              >
                {{ evidenceFeedback(item) }}
              </span>
            </button>
            <div v-if="submittedItems.length" class="evidence-library__actions evidence-library__actions--right">
              <button type="button" data-expand-submitted-evidence @click="openEvidenceGroup('submitted')">
                展开原件匣
              </button>
            </div>
          </section>

        </div>

        <footer class="evidence-footer">
          <div>
            <strong>举证状态</strong>
            <span>{{ completionStatusMessage }}</span>
          </div>
          <button
            v-if="completion?.sealed"
            type="button"
            data-enter-hearing
            @click="enterHearing"
          >
            进入小法庭
          </button>
          <div
            v-else-if="currentPartyCompleted"
            class="evidence-completed"
            data-evidence-completed
          >
            <strong>已封入证据袋</strong>
            <span>等待{{ waitingCounterpartyLabel }}或举证时效结束</span>
          </div>
          <button
            v-else
            type="button"
            data-complete-evidence
            :disabled="completing"
            @click="completeEvidence"
          >
            {{ completing ? "正在封入证据袋…" : "我方举证完成" }}
          </button>
        </footer>
        <p v-if="error" class="evidence-error" role="alert">{{ error }}</p>
      </section>
    </div>

    <div
      v-if="evidenceGateError"
      class="evidence-modal"
      data-evidence-gate-modal
      role="dialog"
      aria-modal="true"
      aria-labelledby="evidence-gate-title"
    >
      <section class="evidence-modal__panel evidence-modal__panel--notice">
        <span class="evidence-kicker">EVIDENCE REQUIRED</span>
        <h2 id="evidence-gate-title">暂不能完成举证</h2>
        <p>{{ evidenceGateError }}</p>
        <button
          type="button"
          data-dismiss-evidence-gate
          @click="dismissEvidenceGate"
        >
          我知道了
        </button>
      </section>
    </div>

    <div
      v-if="selectedEvidence"
      class="evidence-modal"
      data-evidence-detail-modal
      role="dialog"
      aria-modal="true"
      aria-label="证据详情"
      @click.self="closeEvidenceDetail"
    >
      <section class="evidence-modal__panel">
        <header>
          <div>
            <span class="evidence-kicker">EVIDENCE DETAIL</span>
            <h2>{{ evidenceId(selectedEvidence) }}</h2>
            <p>{{ evidenceTypeLabels[selectedEvidence.evidence_type] || selectedEvidence.evidence_type }}</p>
          </div>
          <button type="button" data-close-evidence-modal @click="closeEvidenceDetail">
            关闭
          </button>
        </header>
        <div class="evidence-modal__facts">
          <span>提交方：{{ evidenceOwnerLabel(selectedEvidence) }}</span>
          <span>核验状态：{{ statusLabels[selectedEvidence.verification_status] || selectedEvidence.verification_status || "待核验" }}</span>
          <span>置信度：{{ evidenceConfidenceCopy(selectedEvidence) }}</span>
          <span v-if="evidenceOriginalFilename(selectedEvidence)">
            原始文件：{{ evidenceOriginalFilename(selectedEvidence) }}
          </span>
        </div>
        <article v-if="evidenceFeedback(selectedEvidence)">
          <strong>书记官核验反馈</strong>
          <p>{{ evidenceFeedback(selectedEvidence) }}</p>
        </article>
        <article v-if="evidenceParsedText(selectedEvidence)">
          <strong>解析文本 / OCR</strong>
          <p>{{ evidenceParsedText(selectedEvidence) }}</p>
        </article>
        <a
          v-if="selectedEvidence.content_url"
          class="evidence-modal__link"
          :href="selectedEvidence.content_url"
          :download="evidenceOriginalFilename(selectedEvidence) || evidenceId(selectedEvidence)"
          data-download-evidence
          target="_blank"
          rel="noreferrer"
        >
          下载原始材料
        </a>
      </section>
    </div>

    <div
      v-if="expandedEvidenceGroup"
      class="evidence-modal"
      data-evidence-gallery-modal
      role="dialog"
      aria-modal="true"
      aria-label="证据文件列表"
      @click.self="closeEvidenceGroup"
    >
      <section class="evidence-modal__panel evidence-modal__panel--gallery">
        <header>
          <div>
            <span class="evidence-kicker">EVIDENCE FILES</span>
            <h2>{{ expandedEvidenceTitle }}</h2>
            <p>点击卡片查看详情；下载请使用卡片里的下载入口。</p>
          </div>
          <button type="button" data-close-evidence-gallery @click="closeEvidenceGroup">
            关闭
          </button>
        </header>
        <div class="evidence-gallery-grid">
          <article
            v-for="item in expandedEvidenceItems"
            :key="evidenceId(item)"
            class="evidence-gallery-card"
            data-evidence-gallery-card
            @click="openEvidenceDetail(item)"
          >
            <span
              class="evidence-card__icon evidence-file-icon evidence-file-icon--large"
              :class="evidenceFileIconStatusClass(item)"
              :data-file-kind="evidenceFileIcon(item).kind"
              :aria-label="evidenceFileIcon(item).label"
            >
              <span class="evidence-file-icon__body" aria-hidden="true">
                <span class="evidence-file-icon__landscape"></span>
                <span class="evidence-file-icon__play"></span>
                <span class="evidence-file-icon__lines"></span>
              </span>
              <span class="evidence-file-icon__badge" data-file-badge>
                {{ evidenceFileIcon(item).badge }}
              </span>
              <span
                v-if="evidenceSubmissionStatus(item) === 'SUBMITTED'"
                class="evidence-file-icon__lock"
                aria-hidden="true"
              ></span>
            </span>
            <strong>{{ evidenceOriginalFilename(item) || evidenceId(item) }}</strong>
            <small>{{ evidenceSubmissionStatusLabel(item) }} · {{ statusLabels[item.verification_status] || item.verification_status || "待核验" }}</small>
            <a
              v-if="item.content_url"
              :href="item.content_url"
              :download="evidenceOriginalFilename(item) || evidenceId(item)"
              data-gallery-download-evidence
              @click.stop
            >
              下载
            </a>
          </article>
        </div>
      </section>
    </div>
  </RoomShell>
</template>

<style scoped>
.evidence-room {
  --evidence-panel-height: 740px;
  display: grid;
  grid-template-columns: minmax(520px, 1.05fr) minmax(480px, .95fr);
  gap: 18px;
  align-items: start;
  min-width: 0;
}

.evidence-room__conversation,
.evidence-board {
  height: var(--evidence-panel-height);
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  padding: 18px;
  overflow: hidden;
  background: #ffffffbf;
  border: 1px solid #dfe8f4;
  border-radius: 28px;
  box-shadow: 0 20px 55px #556d9512;
}

.evidence-room__conversation {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
}

.evidence-room__case-note {
  padding: 16px;
  margin-bottom: 14px;
  background: linear-gradient(135deg, #eef8ff, #fff5df);
  border-radius: 20px;
}

.evidence-room__case-note span,
.evidence-kicker {
  color: #7186aa;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}

.evidence-room__case-note h2 {
  margin: 5px 0;
  color: #34435c;
  font-size: 21px;
}

.evidence-room__case-note p {
  margin: 0;
  color: #6f7d92;
  line-height: 1.6;
}

.evidence-room__conversation-frame {
  display: grid;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.evidence-room__conversation-frame :deep(.conversation-stream) {
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

.evidence-room__conversation-frame :deep(.conversation-stream__messages),
.evidence-room__conversation-frame :deep(.conversation-stream__composer),
.evidence-room__conversation-frame :deep(.conversation-stream__composer > div) {
  min-width: 0;
}

.evidence-room__conversation-frame :deep(.conversation-stream__message) {
  min-width: 0;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-room__conversation-frame :deep(.conversation-stream__message header),
.evidence-room__conversation-frame :deep(.conversation-stream__message p) {
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

:deep(.room-shell__header),
:deep(.room-shell__header > div),
:deep(.room-shell__status),
:deep(.room-shell__boundary) {
  min-width: 0;
}

:deep(.room-shell__boundary) {
  width: auto;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-board {
  position: relative;
  display: grid;
  grid-template-rows: 76px 86px minmax(0, 1fr) 60px;
  gap: 10px;
}

.evidence-board__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  min-width: 0;
  min-height: 0;
}

.evidence-board__header > div {
  min-width: 0;
}

.evidence-board__header h2 {
  margin: 5px 0 2px;
  color: #34435c;
  font-size: 23px;
}

.evidence-board__header p {
  margin: 0;
  color: #718096;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-board__badge,
.privacy-seal,
.shared-seal {
  height: max-content;
  padding: 6px 9px;
  color: #6179a0;
  background: #fff;
  border: 1px solid #e2ebf6;
  border-radius: 999px;
  font-size: 11px;
  white-space: nowrap;
}

.evidence-uploader {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  padding: 12px;
  background: linear-gradient(135deg, #fff9df, #edfbf4 52%, #edf6ff);
  border: 1px solid #dce9e4;
  border-radius: 18px;
}

.evidence-uploader > div {
  min-width: 0;
}

.evidence-uploader__illustration {
  display: flex;
  gap: 2px;
  font-size: 20px;
}

.evidence-uploader strong {
  display: block;
  color: #33435c;
  font-size: 13px;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-uploader small {
  display: block;
  color: #718096;
  font-size: 11px;
  line-height: 1.4;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-uploader__button,
.evidence-footer button {
  padding: 10px 13px;
  color: white;
  background: linear-gradient(135deg, #55b8df, #8585ef);
  border: 0;
  border-radius: 13px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 900;
  white-space: nowrap;
}

.evidence-uploader__button input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.evidence-board__list {
  display: grid;
  align-content: start;
  min-width: 0;
  min-height: 0;
  gap: 10px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding-right: 4px;
}

.evidence-library {
  min-width: 0;
  padding: 13px;
  background: #ffffffd9;
  border: 1px solid #e0e8f2;
  border-radius: 20px;
  box-shadow: 0 14px 30px #5b74910d;
}

.evidence-library--private {
  background: linear-gradient(160deg, #f5f0ff, #fff);
}

.evidence-library--pending {
  background: linear-gradient(160deg, #fff7dd, #fff);
  border-color: #f1dfad;
}

.evidence-library--shared {
  background: linear-gradient(160deg, #eaf8ff, #fff);
}

.evidence-library header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 8px;
}

.evidence-library h3 {
  margin: 3px 0 0;
  color: #31405a;
  font-size: 16px;
}

.privacy-seal {
  color: #725e98;
}

.shared-seal {
  color: #2e7b72;
}

.evidence-grid {
  display: grid;
  gap: 9px;
}

.evidence-card {
  position: relative;
  display: grid;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  grid-template-columns: auto minmax(0, 1fr) minmax(132px, auto);
  grid-template-areas:
    "icon main meta"
    "description description description";
  gap: 10px;
  align-items: start;
  padding: 11px;
  margin-top: 8px;
  text-align: left;
  background: #fff;
  border: 1px solid #e4eaf2;
  border-radius: 16px;
  cursor: pointer;
  transition: transform .16s ease, box-shadow .16s ease, border-color .16s ease;
}

.evidence-card--pending {
  padding-right: 34px;
}

.evidence-card:hover {
  transform: translateY(-1px);
  border-color: #c9d8ef;
  box-shadow: 0 12px 26px #536e9017;
}

.evidence-card__remove {
  position: absolute;
  top: 7px;
  right: 7px;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  color: #a04f58;
  background: #fff0f2;
  border: 1px solid #ffd7dc;
  border-radius: 999px;
  cursor: pointer;
  font-size: 15px;
  font-weight: 900;
  line-height: 1;
}

.evidence-card__remove:disabled {
  cursor: default;
  opacity: .55;
}

.evidence-card__icon {
  position: relative;
  display: grid;
  grid-area: icon;
  place-items: center;
  width: 42px;
  height: 44px;
  overflow: visible;
  background: linear-gradient(145deg, var(--file-tint, #f4f7fb), #fff);
  border: 1px solid var(--file-border, #dbe5f1);
  border-radius: 15px;
  box-shadow: inset 0 1px 0 #ffffffd9, 0 8px 18px #5b749014;
}

.evidence-file-icon--pending {
  border-style: dashed;
}

.evidence-file-icon--large {
  width: 58px;
  height: 62px;
  margin-bottom: 2px;
  justify-self: start;
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
  place-items: center;
  width: 25px;
  height: 30px;
  overflow: hidden;
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
  letter-spacing: .02em;
  line-height: 1;
}

.evidence-file-icon__lock {
  position: absolute;
  top: -4px;
  right: -3px;
  width: 12px;
  height: 10px;
  background: #fff;
  border: 1px solid color-mix(in srgb, var(--file-accent, #7890aa) 35%, #ffffff);
  border-radius: 3px;
  box-shadow: 0 3px 8px #445b781a;
}

.evidence-file-icon__lock::before {
  position: absolute;
  left: 2px;
  top: -7px;
  width: 6px;
  height: 8px;
  content: "";
  border: 1.5px solid color-mix(in srgb, var(--file-accent, #7890aa) 72%, #ffffff);
  border-bottom: 0;
  border-radius: 999px 999px 0 0;
}

.evidence-card__main {
  grid-area: main;
  display: grid;
  min-width: 0;
  gap: 4px;
}

.evidence-card__meta {
  grid-area: meta;
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  min-width: 0;
  gap: 6px;
}

.evidence-card strong {
  overflow: hidden;
  color: #3b4960;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-card__filename {
  display: block;
  min-width: 0;
  overflow: hidden;
  color: #8a96a8;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-card__status {
  padding: 5px 7px;
  color: #647fb0;
  background: #edf3fb;
  border-radius: 999px;
  font-size: 10px;
  font-style: normal;
  line-height: 1;
  white-space: normal;
  overflow-wrap: anywhere;
}

.evidence-card__description {
  grid-column: 1 / -1;
  grid-area: description;
  min-width: 0;
  color: #63728a;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.verification-pill,
.confidence-pill {
  padding: 5px 7px;
  border-radius: 999px;
  font-size: 10px;
  line-height: 1;
  white-space: normal;
  overflow-wrap: anywhere;
}

.verification-pill[data-verification="VERIFIED"] { color: #25704e; background: #dcf5e8; }
.verification-pill[data-verification="PENDING"] { color: #68778e; background: #eef3f8; }
.verification-pill[data-verification="PLAUSIBLE"] { color: #39708e; background: #e0f2ff; }
.verification-pill[data-verification="SUSPICIOUS"] { color: #9b671b; background: #fff0c9; }
.verification-pill[data-verification="REJECTED"] { color: #a34b55; background: #ffeaed; }
.verification-pill[data-verification="NEEDS_HUMAN_REVIEW"] { color: #6e5799; background: #eee7ff; }
.confidence-pill[data-confidence="high"] { color: #287552; background: #e2f7eb; }
.confidence-pill[data-confidence="medium"] { color: #a07816; background: #fff3c4; }
.confidence-pill[data-confidence="low"] { color: #a04f58; background: #ffeaee; }
.confidence-pill[data-confidence="unknown"] { color: #68778e; background: #eef3f8; }

.evidence-empty {
  margin: 8px 0 0;
  color: #8b96a8;
  font-size: 13px;
}

.evidence-library__actions {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
}

.evidence-library__actions--right {
  justify-content: flex-end;
}

.evidence-library__actions button {
  padding: 8px 11px;
  color: #50668b;
  background: #f5f8fc;
  border: 1px solid #dfe8f4;
  border-radius: 12px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 900;
}

.evidence-library__actions [data-submit-evidence-batch] {
  color: #fff;
  background: linear-gradient(135deg, #55b8df, #8585ef);
  border-color: transparent;
}

.evidence-library__actions button:disabled {
  cursor: default;
  opacity: .6;
}

.evidence-footer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  align-items: center;
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  padding-top: 10px;
  border-top: 1px dashed #d7e2ef;
}

.evidence-footer div {
  display: grid;
  min-width: 0;
  gap: 4px;
  color: #40506a;
}

.evidence-footer span {
  color: #7a879b;
  font-size: 12px;
  line-height: 1.45;
}

.evidence-footer button {
  background: linear-gradient(135deg, #ff8b70, #ef6e91);
}

.evidence-footer button:disabled {
  cursor: default;
  opacity: .6;
}

.evidence-completed {
  min-width: 178px;
  padding: 10px 14px;
  color: #357052;
  background: linear-gradient(135deg, #e2f7eb, #eef7ff);
  border: 1px solid #c5ead6;
  border-radius: 16px;
  text-align: center;
}

.evidence-completed strong { color: #2d6d4f; }
.evidence-completed span { color: #5c7a6b; }
.evidence-error {
  position: absolute;
  left: 18px;
  right: 18px;
  bottom: 76px;
  z-index: 2;
  margin: 0;
  padding: 9px 11px;
  color: #a84552;
  background: #fff1f3f2;
  border: 1px solid #ffd5db;
  border-radius: 12px;
  box-shadow: 0 10px 28px #7e43501f;
  font-size: 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-modal {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  padding: 24px;
  background: #25354a66;
  backdrop-filter: blur(8px);
}

.evidence-modal__panel {
  display: grid;
  width: min(680px, 100%);
  max-height: min(720px, 88vh);
  gap: 14px;
  padding: 22px;
  overflow-y: auto;
  background: linear-gradient(135deg, #ffffff, #f4f9ff);
  border: 1px solid #dce7f4;
  border-radius: 26px;
  box-shadow: 0 28px 80px #22304740;
}

.evidence-modal__panel--notice {
  width: min(420px, 100%);
  gap: 12px;
}

.evidence-modal__panel--notice > button {
  justify-self: end;
}

.evidence-modal__panel--gallery {
  width: min(760px, 100%);
}

.evidence-gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
}

.evidence-gallery-card {
  display: grid;
  gap: 8px;
  align-content: start;
  min-height: 146px;
  padding: 14px;
  color: #40506a;
  background: #ffffffd9;
  border: 1px solid #e2ebf6;
  border-radius: 18px;
  cursor: pointer;
}

.evidence-gallery-card strong,
.evidence-gallery-card small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-gallery-card a {
  width: max-content;
  padding: 7px 9px;
  color: #5f6fd8;
  background: #eef3ff;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 900;
  text-decoration: none;
}

.evidence-modal__panel header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.evidence-modal__panel h2 {
  margin: 5px 0;
  color: #2f3f58;
  font-size: 20px;
}

.evidence-modal__panel p {
  margin: 0;
  color: #66758d;
  line-height: 1.7;
}

.evidence-modal__panel button,
.evidence-modal__link {
  height: max-content;
  padding: 9px 12px;
  color: #5f6fd8;
  background: #eef3ff;
  border: 0;
  border-radius: 12px;
  cursor: pointer;
  font-weight: 800;
  text-decoration: none;
}

.evidence-modal__facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.evidence-modal__facts span,
.evidence-modal__panel article {
  padding: 11px 12px;
  color: #43526c;
  background: #ffffffd9;
  border: 1px solid #e2ebf6;
  border-radius: 14px;
}

.evidence-modal__panel article strong {
  display: block;
  margin-bottom: 6px;
  color: #33435c;
}

@media (max-width: 1120px) {
  .evidence-room { grid-template-columns: 1fr; }
}

@media (max-width: 620px) {
  .evidence-modal__facts {
    grid-template-columns: 1fr;
  }

  .evidence-uploader__button,
  .evidence-footer button {
    text-align: center;
  }

  .evidence-footer {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px;
  }
}

@media (max-width: 360px) {
  :deep(.room-shell__header) {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 4px 8px;
    align-items: end;
  }

  :deep(.room-shell__header > div:first-child),
  :deep(.room-shell__status) {
    display: contents;
  }

  :deep(.room-shell__eyebrow),
  :deep(.room-shell__status > span) {
    display: none;
  }

  :deep(.room-shell__header h1) {
    grid-column: 1 / -1;
    grid-row: 1;
    min-width: 0;
    margin: 0;
    font-size: 26px;
    line-height: 1.15;
    white-space: normal;
    overflow-wrap: anywhere;
    word-break: break-word;
  }

  :deep(.room-shell__header p) {
    grid-column: 1;
    grid-row: 2;
    min-width: 0;
    margin: 0;
    white-space: normal;
    overflow-wrap: anywhere;
    word-break: break-word;
  }

  :deep([data-evidence-countdown]) {
    grid-column: 2;
    grid-row: 2;
    min-width: 0;
    white-space: nowrap;
  }

  :deep(.room-shell__boundary) {
    align-items: flex-start;
    line-height: 1.45;
  }

  .evidence-board {
    grid-template-rows: 88px 96px minmax(0, 1fr) 72px;
    gap: 12px;
  }

  .evidence-board__header {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      "title badge"
      "copy copy";
    gap: 4px 8px;
    align-items: start;
  }

  .evidence-board__header > div {
    display: contents;
  }

  .evidence-board__header .evidence-kicker {
    display: none;
  }

  .evidence-board__header h2 {
    grid-area: title;
    margin: 0;
    font-size: 21px;
    line-height: 1.25;
  }

  .evidence-board__header p {
    grid-area: copy;
    font-size: 12px;
    line-height: 1.4;
  }

  .evidence-board__badge {
    grid-area: badge;
    padding: 5px 7px;
  }

  .evidence-uploader {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px;
    padding: 10px;
  }

  .evidence-uploader__illustration,
  .evidence-uploader .evidence-kicker,
  .evidence-card__icon {
    display: none;
  }

  .evidence-uploader__button {
    padding: 9px 10px;
  }

  .evidence-card {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      "main"
      "meta"
      "description";
    gap: 7px;
    padding: 10px;
  }

  .evidence-card--pending {
    padding-right: 34px;
  }

  .evidence-card__meta {
    justify-content: flex-start;
  }

  .evidence-footer {
    gap: 8px;
    padding-top: 8px;
  }

  .evidence-footer div {
    gap: 2px;
  }

  .evidence-footer span {
    font-size: 11px;
    line-height: 1.35;
  }

  .evidence-footer button {
    padding: 9px 10px;
  }

  .evidence-completed {
    min-width: 0;
    padding: 8px 10px;
  }

  .evidence-error {
    left: 18px;
    right: 18px;
    bottom: 90px;
  }
}
</style>
