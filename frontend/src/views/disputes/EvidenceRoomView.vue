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
import { newIdempotencyKey } from "../../api/client";
import {
  extractAgentRunDescriptor,
  loadActiveAgentRuns,
  resultRoomMessage,
} from "../../api/agentStream";
import { evidenceApi } from "../../api/evidence";
import { roomApi } from "../../api/rooms";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import AgentStreamErrorDialog from "../../components/room/AgentStreamErrorDialog.vue";
import ConversationStream from "../../components/room/ConversationStream.vue";
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
} from "../../stores/agentStream";

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
  modelHealthLoader: { type: Function, default: null },
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
const streamError = ref("");
const evidenceGateError = ref("");
const agentState = ref("LISTENING");
const fileInput = ref(null);
const messages = ref([...(props.initialMessages || [])]);
const selectedEvidence = ref(null);
const selectedEvidenceMode = ref("evidence");
const expandedEvidenceGroup = ref(null);
const evidenceGateModal = ref(null);
const evidenceDetailModal = ref(null);
const evidenceGalleryModal = ref(null);
const modalStack = ref([]);
const deletingEvidenceIds = ref(new Set());
const eventState = reactive(createRoomState());
const modelConnectionState = ref("checking");
const modalTriggers = new Map();
let eventAbortController = new AbortController();
let eventStreamActive = false;
let workspaceGeneration = 0;
let modalKeydownAttached = false;
let modelHealthTimer = null;
let modelHealthInFlight = null;

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
const shouldDiscoverActiveEvidenceRuns = computed(() =>
  props.initialMessages === null &&
  props.initialCatalog === null &&
  props.initialCompletion === null &&
  !props.eventStreamer,
);
const displayedMessages = computed(() => {
  const hasDossierSpecificOpening = messages.value.some((message) =>
    isDossierSpecificOpeningMessage(message),
  );
  const visibleMessages = hasDossierSpecificOpening
    ? messages.value.filter((message) => !isSupersededOpeningMessage(message))
    : messages.value;
  return visibleMessages.map((message) => ({
    ...message,
    message_text: displayEvidenceReferences(message?.message_text),
  }));
});
const evidenceStreamingRuns = computed(() =>
  activeAgentStreams({
    caseId: caseId.value,
    roomType: "EVIDENCE",
    actorId: effectiveActor.value.id,
    actorRole: effectiveActor.value.role,
  }).map((run) => ({
    ...run,
    content: displayEvidenceReferences(run.content),
  })),
);
const modelConnected = computed(() => modelConnectionState.value === "connected");
const modelConnectionLabel = computed(() => {
  if (evidenceStreamingRuns.value.length) return "数字人正在输出";
  if (modelConnectionState.value === "connected") return "数字人已连接";
  if (modelConnectionState.value === "checking") return "连接检测中";
  return "数字人未连接";
});
const initialEvidencePlanReady = computed(() =>
  displayedMessages.value.some((message) => isCurrentEvidenceClerkMessage(message)),
);
const evidenceConversationEmptyText = computed(() => {
  if (!modelConnected.value) {
    return "数字人未连接，恢复连接后将生成首轮核验计划。";
  }
  if (!initialEvidencePlanReady.value) {
    return "证据书记官正在依据接待卷宗生成首轮核验计划，请稍候。";
  }
  return "证据书记官的首轮核验计划正在同步到对话记录。";
});
const evidenceComposerDisabled = computed(() =>
  !modelConnected.value ||
  (shouldEnsureOpening.value && !initialEvidencePlanReady.value) ||
  uploading.value ||
  submittingBatch.value ||
  completing.value ||
  evidenceStreamingRuns.value.length > 0,
);
const evidenceWorkStatus = computed(() => {
  if (evidenceStreamingRuns.value.length || agentState.value === "STREAMING") {
    return "STREAMING";
  }
  if (!modelConnected.value) {
    return modelConnectionState.value === "checking"
      ? "MODEL_CONNECTING"
      : "MODEL_DISCONNECTED";
  }
  if (error.value) return "ERROR";
  if (completion.value?.sealed) return "HANDOFF";
  if (!initialEvidencePlanReady.value && shouldEnsureOpening.value) {
    return "GENERATING_INITIAL";
  }
  if (uploading.value || submittingBatch.value || completing.value || agentState.value === "THINKING") {
    return "ANALYSING";
  }
  return "READY";
});
const evidenceWorkStatusCopy = computed(() => {
  const copies = {
    MODEL_CONNECTING: {
      eyebrow: "MODEL STATUS",
      title: "正在检测数字人连接",
      description: "正在确认多模态模型是否可用，连接成功后将生成首轮核验计划。",
      tone: "working",
    },
    MODEL_DISCONNECTED: {
      eyebrow: "MODEL OFFLINE",
      title: "数字人未连接",
      description: "模型服务暂不可用；恢复连接后才能生成核验计划或提交说明。",
      tone: "error",
    },
    GENERATING_INITIAL: {
      eyebrow: "EVIDENCE STATUS",
      title: "证据书记官正在生成首轮核验计划",
      description: "正在读取接待卷宗、争议焦点和待核验事实，确定本轮材料方向。",
      tone: "working",
    },
    ANALYSING: {
      eyebrow: "EVIDENCE STATUS",
      title: "证据书记官正在分析本轮材料",
      description: "正在核对来源、完整性、关联性和需要人工复核的风险信号。",
      tone: "working",
    },
    STREAMING: {
      eyebrow: "LIVE GENERATION",
      title: "证据书记官正在流式输出",
      description: "核验意见正在生成；完成校验并正式入库前，当前内容仅作实时展示。",
      tone: "streaming",
    },
    READY: {
      eyebrow: "READY",
      title: "证据书记官已就绪",
      description: "可说明材料来源、形成时间和可证明范围，或上传待提交证据。",
      tone: "ready",
    },
    HANDOFF: {
      eyebrow: "HANDOFF",
      title: "证据室已封存",
      description: "本轮证据核验已完成，卷宗和复核记录已交接至庭审阶段。",
      tone: "handoff",
    },
    ERROR: {
      eyebrow: "EVIDENCE ERROR",
      title: "证据书记官生成失败",
      description: "模型响应未完成，当前不会写入兜底内容；请在服务恢复后重试。",
      tone: "error",
    },
  };
  return copies[evidenceWorkStatus.value] || copies.READY;
});
const items = computed(() => catalog.value?.items || []);
const actorOwnedItems = computed(() =>
  items.value.filter(
    (item) =>
      evidenceField(item, "submitted_by_role", "submittedByRole", "") === role.value,
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
const humanReviewScopeItems = computed(() =>
  role.value === "PLATFORM_REVIEWER"
    ? items.value.filter((item) => evidenceSubmissionStatus(item) === "SUBMITTED")
    : submittedItems.value,
);
const humanReviewItems = computed(() =>
  humanReviewScopeItems.value.filter((item) => evidenceRequiresHumanReview(item)),
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
  // An active evidence AgentRun proves the model stream is live even when the
  // durable room-event channel has not connected yet.
  if (evidenceStreamingRuns.value.length > 0) return "connected";
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

const humanReviewReasonLabels = {
  VISUAL_DETAIL_UNCERTAIN: "图片或视频细节无法由模型可靠判定",
  FINE_VISUAL_DAMAGE_REQUIRES_HUMAN: "细微外观损伤需要人工查看原图",
  VISUAL_NOT_INSPECTED: "模型未完成原始画面检查",
  SOURCE_HASH_MISSING: "原件缺少可核对的入库哈希",
  LOW_AUTHENTICITY_SCORE: "真实性评分偏低",
  LOW_COMPLETENESS_SCORE: "材料完整性评分偏低",
  LOW_ASSESSMENT_CONFIDENCE: "模型对本次核验的把握不足",
  HIGH_RISK_FLAG: "模型识别到高风险信号",
  ASSESSMENT_MISSING: "模型未返回完整的结构化核验结果",
  UNKNOWN_FACT_REFERENCE: "证据引用了接待卷宗之外的事实，需人工映射",
  SOURCE_PROVENANCE_UNVERIFIED: "材料来源或流转链路尚未核实",
  POSSIBLE_EDITING: "材料可能存在编辑或拼接痕迹",
  LOW_IMAGE_QUALITY: "画面清晰度不足",
  OCR_AMBIGUOUS: "文字识别结果存在歧义",
  METADATA_MISSING: "缺少可用于交叉核验的元数据",
  CROSS_SOURCE_CONFLICT: "与其他材料存在冲突",
  CONTENT_NOT_RELEVANT: "材料与当前争议事实的关联性不足",
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

// 业务位置：【前端证据室】evidenceField：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceField(item, snakeCaseKey, camelCaseKey, fallback = "") {
  return item?.[snakeCaseKey] ?? item?.[camelCaseKey] ?? fallback;
}

// 业务位置：【前端证据室】evidenceId：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceId(item) {
  return evidenceField(item, "evidence_id", "evidenceId", "");
}

// 业务位置：【前端证据室】evidenceSubmissionStatus：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceSubmissionStatus(item) {
  return String(
    evidenceField(item, "submission_status", "submissionStatus", "SUBMITTED"),
  ).toUpperCase();
}

// 业务位置：【前端证据室】evidenceSubmissionStatusLabel：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceSubmissionStatusLabel(item) {
  const status = evidenceSubmissionStatus(item);
  if (status === "PENDING_SUBMISSION") return "待提交";
  if (status === "SUBMITTED") return "已提交";
  if (status === "VOIDED") return "已作废";
  return status || "待确认";
}

// 业务位置：【前端证据室】evidenceVerificationStatus：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceVerificationStatus(item) {
  return String(
    evidenceField(item, "verification_status", "verificationStatus", "PENDING"),
  ).toUpperCase();
}

// 业务位置：【前端证据室】isDeletingEvidence：判断 当前可见证据和附件 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function isDeletingEvidence(item) {
  return deletingEvidenceIds.value.has(evidenceId(item));
}

// 业务位置：【前端证据室】percentageScore：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function percentageScore(raw) {
  if (raw === null || raw === undefined || raw === "") return null;
  const numeric = Number(raw);
  if (!Number.isFinite(numeric)) return null;
  return Math.min(100, Math.max(0, numeric <= 1 ? Math.round(numeric * 100) : Math.round(numeric)));
}

// 业务位置：【前端证据室】evidenceAssessmentConfidenceRaw：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceAssessmentConfidenceRaw(item) {
  return evidenceField(
    item,
    "assessment_confidence",
    "assessmentConfidence",
    evidenceField(
      item,
      "confidence_score",
      "confidenceScore",
      evidenceField(item, "confidence", "confidence", null),
    ),
  );
}

// 业务位置：【前端证据室】evidenceConfidenceScore：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceConfidenceScore(item) {
  return percentageScore(evidenceAssessmentConfidenceRaw(item));
}

// 业务位置：【前端证据室】evidenceConfidenceLevel：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceConfidenceLevel(item) {
  const score = evidenceConfidenceScore(item);
  if (score !== null) {
    if (score >= 80) return confidenceLabels.HIGH;
    if (score >= 50) return confidenceLabels.MEDIUM;
    return confidenceLabels.LOW;
  }
  const value = String(
    evidenceField(item, "confidence_level", "confidenceLevel", "UNKNOWN"),
  ).toUpperCase();
  return confidenceLabels[value] || confidenceLabels.UNKNOWN;
}

// 业务位置：【前端证据室】evidenceConfidenceTone：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceConfidenceTone(item) {
  const score = evidenceConfidenceScore(item);
  if (score !== null) {
    if (score >= 80) return "high";
    if (score >= 50) return "medium";
    return "low";
  }
  const value = String(
    evidenceField(item, "confidence_level", "confidenceLevel", "UNKNOWN"),
  ).toLowerCase();
  if (["high", "medium", "low"].includes(value)) return value;
  return "unknown";
}

// 业务位置：【前端证据室】evidenceConfidenceCopy：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceConfidenceCopy(item) {
  const score = evidenceConfidenceScore(item);
  const label = evidenceConfidenceLevel(item);
  return score === null ? label : `${score}% · ${label}`;
}

// 业务位置：【前端证据室】evidenceMetricScore：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceMetricScore(item, snakeCaseKey, camelCaseKey) {
  return percentageScore(evidenceField(item, snakeCaseKey, camelCaseKey, null));
}

// 业务位置：【前端证据室】evidenceMetricCopy：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceMetricCopy(item, snakeCaseKey, camelCaseKey) {
  const score = evidenceMetricScore(item, snakeCaseKey, camelCaseKey);
  return score === null ? "待评估" : `${score}%`;
}

// 业务位置：【前端证据室】normalizedList：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function normalizedList(value) {
  if (Array.isArray(value)) {
    return value.map((entry) => String(entry || "").trim()).filter(Boolean);
  }
  if (value === null || value === undefined || value === "") return [];
  return [String(value).trim()].filter(Boolean);
}

// 业务位置：【前端证据室】evidenceListField：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceListField(item, snakeCaseKey, camelCaseKey) {
  return normalizedList(evidenceField(item, snakeCaseKey, camelCaseKey, []));
}

// 业务位置：【前端证据室】evidenceLimitations：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceLimitations(item) {
  return evidenceListField(item, "limitations", "limitations");
}

// 业务位置：【前端证据室】evidenceHumanReviewReasons：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceHumanReviewReasons(item) {
  return evidenceListField(
    item,
    "human_review_reason_codes",
    "humanReviewReasonCodes",
  );
}

// 业务位置：【前端证据室】evidenceHumanReviewInstructions：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceHumanReviewInstructions(item) {
  return evidenceListField(
    item,
    "human_review_instructions",
    "humanReviewInstructions",
  );
}

// 业务位置：【前端证据室】humanReviewReasonLabel：围绕 人工审核关注点和陪审团提示 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function humanReviewReasonLabel(reasonCode) {
  const code = String(reasonCode || "").trim();
  if (code.startsWith("VISUAL_INPUT_")) return "原始视觉内容未能安全加载";
  if (code.startsWith("ASSESSMENT_MISSING_")) return "模型未返回完整的结构化核验结果";
  return humanReviewReasonLabels[code] || code.replaceAll("_", " ");
}

// 业务位置：【前端证据室】evidenceRequiresHumanReview：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceRequiresHumanReview(item) {
  const explicit = evidenceField(
    item,
    "requires_human_review",
    "requiresHumanReview",
    false,
  );
  return explicit === true || String(explicit).toLowerCase() === "true" ||
    evidenceVerificationStatus(item) === "NEEDS_HUMAN_REVIEW";
}

// 业务位置：【前端证据室】evidenceOwnerLabel：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceOwnerLabel(item) {
  const owner = evidenceField(item, "submitted_by_role", "submittedByRole", "");
  if (owner === "MERCHANT") return "商家提交";
  if (owner === "USER") return "用户提交";
  if (owner === "PLATFORM_REVIEWER") return "平台提交";
  return owner || "来源待确认";
}

// 业务位置：【前端证据室】evidenceFeedback：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceFeedback(item) {
  return evidenceField(item, "verification_feedback", "verificationFeedback", "");
}

// 业务位置：【前端证据室】evidenceOriginalFilename：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceOriginalFilename(item) {
  return evidenceField(item, "original_filename", "originalFilename", "");
}

function compactEvidenceDisplayName(item) {
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

function displayEvidenceReferences(value, fallbackItem = null) {
  const replaced = String(value || "").replace(
    /(?:证据\s*)?EVIDENCE_[A-Za-z0-9_-]+/g,
    (internalId) => {
      const normalizedId = internalId.match(/EVIDENCE_[A-Za-z0-9_-]+/)?.[0] || "";
      const mappedItem = items.value.find(
        (candidate) => evidenceId(candidate) === normalizedId,
      ) || (fallbackItem && evidenceId(fallbackItem) === normalizedId
        ? fallbackItem
        : null);
      return mappedItem
        ? `证据：${compactEvidenceDisplayName(mappedItem)}`
        : "该证据";
    },
  );
  return replaced.replace(
    /(证据：[^，。；\s（）()]{1,6})[（(]文件名[:：][^）)]+[）)]/g,
    "$1",
  );
}

function evidenceFeedbackDisplay(item) {
  return displayEvidenceReferences(evidenceFeedback(item), item);
}

// 业务位置：【前端证据室】evidenceFileSourceName：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceFileSourceName(item) {
  return evidenceOriginalFilename(item) || evidenceField(item, "content_url", "contentUrl", "");
}

// 业务位置：【前端证据室】fileExtension：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function fileExtension(value) {
  const cleanValue = String(value || "").split(/[?#]/)[0];
  const fileName = cleanValue.split(/[\\/]/).pop() || "";
  const lastDotIndex = fileName.lastIndexOf(".");
  if (lastDotIndex <= 0 || lastDotIndex === fileName.length - 1) return "";
  return fileName.slice(lastDotIndex + 1).toLowerCase();
}

// 业务位置：【前端证据室】evidenceFileIcon：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

// 业务位置：【前端证据室】evidenceFileIconStatusClass：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function evidenceFileIconStatusClass(item) {
  return evidenceSubmissionStatus(item) === "PENDING_SUBMISSION"
    ? "evidence-file-icon--pending"
    : "evidence-file-icon--submitted";
}

// 业务位置：【前端证据室】modalRoot：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function modalRoot(modalName) {
  if (modalName === "gate") return evidenceGateModal.value;
  if (modalName === "detail") return evidenceDetailModal.value;
  if (modalName === "gallery") return evidenceGalleryModal.value;
  return null;
}

// 业务位置：【前端证据室】modalDepth：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function modalDepth(modalName) {
  const index = modalStack.value.indexOf(modalName);
  return 40 + Math.max(index, 0);
}

// 业务位置：【前端证据室】isTopModal：判断 当前阶段业务数据 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function isTopModal(modalName) {
  return modalStack.value.at(-1) === modalName;
}

// 业务位置：【前端证据室】isModalCovered：判断 当前阶段业务数据 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function isModalCovered(modalName) {
  const index = modalStack.value.indexOf(modalName);
  return index >= 0 && index < modalStack.value.length - 1;
}

// 业务位置：【前端证据室】modalTrigger：执行 当前阶段业务数据 对应的业务动作，并将结果交给 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function modalTrigger(event) {
  const eventTarget = event?.currentTarget;
  if (eventTarget instanceof HTMLElement) return eventTarget;
  return document.activeElement instanceof HTMLElement
    ? document.activeElement
    : null;
}

// 业务位置：【前端证据室】modalFocusableElements：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function modalFocusableElements(modalName) {
  const root = modalRoot(modalName);
  if (!root) return [];
  return [...root.querySelectorAll(
    'button:not([disabled]), a[href], input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )].filter(
    (element) =>
      element instanceof HTMLElement &&
      element.getAttribute("aria-hidden") !== "true" &&
      !element.closest("[inert]"),
  );
}

// 业务位置：【前端证据室】focusModal：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function focusModal(modalName) {
  const root = modalRoot(modalName);
  if (!root || !isTopModal(modalName)) return;
  const initialFocus = root.querySelector("[data-modal-initial-focus]");
  const target = initialFocus || modalFocusableElements(modalName)[0] || root;
  if (target instanceof HTMLElement) target.focus();
}

// 业务位置：【前端证据室】attachModalKeydown：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function attachModalKeydown() {
  if (modalKeydownAttached) return;
  document.addEventListener("keydown", handleModalKeydown);
  modalKeydownAttached = true;
}

// 业务位置：【前端证据室】detachModalKeydown：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function detachModalKeydown() {
  if (!modalKeydownAttached) return;
  document.removeEventListener("keydown", handleModalKeydown);
  modalKeydownAttached = false;
}

// 业务位置：【前端证据室】openModal：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function openModal(modalName, event) {
  const trigger = modalTrigger(event);
  if (trigger) modalTriggers.set(modalName, trigger);
  modalStack.value = [
    ...modalStack.value.filter((name) => name !== modalName),
    modalName,
  ];
  attachModalKeydown();
  void nextTick(() => focusModal(modalName));
}

// 业务位置：【前端证据室】closeModal：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function closeModal(modalName, options = {}) {
  const wasTopModal = isTopModal(modalName);
  const trigger = modalTriggers.get(modalName);
  modalTriggers.delete(modalName);
  modalStack.value = modalStack.value.filter((name) => name !== modalName);
  if (!modalStack.value.length) detachModalKeydown();
  if (!wasTopModal || options.restoreFocus === false) return;
  void nextTick(() => {
    if (trigger?.isConnected) {
      trigger.focus();
      return;
    }
    const nextModal = modalStack.value.at(-1);
    if (nextModal) focusModal(nextModal);
  });
}

// 业务位置：【前端证据室】trapModalFocus：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function trapModalFocus(event, modalName) {
  const focusableElements = modalFocusableElements(modalName);
  if (!focusableElements.length) {
    event.preventDefault();
    focusModal(modalName);
    return;
  }
  const currentIndex = focusableElements.indexOf(document.activeElement);
  const nextIndex = event.shiftKey
    ? currentIndex <= 0
      ? focusableElements.length - 1
      : currentIndex - 1
    : currentIndex < 0 || currentIndex === focusableElements.length - 1
      ? 0
      : currentIndex + 1;
  event.preventDefault();
  focusableElements[nextIndex].focus();
}

// 业务位置：【前端证据室】closeTopModal：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function closeTopModal() {
  const topModal = modalStack.value.at(-1);
  if (topModal === "detail") closeEvidenceDetail();
  if (topModal === "gallery") closeEvidenceGroup();
  if (topModal === "gate") dismissEvidenceGate();
}

// 业务位置：【前端证据室】handleModalKeydown：执行 当前阶段业务数据 对应的业务动作，并将结果交给 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function handleModalKeydown(event) {
  const topModal = modalStack.value.at(-1);
  if (!topModal) return;
  if (event.key === "Escape") {
    event.preventDefault();
    closeTopModal();
    return;
  }
  if (event.key === "Tab") trapModalFocus(event, topModal);
}

// 业务位置：【前端证据室】resetModalController：更新 当前阶段业务数据 的消息、缓存或持久记录，避免旧回合数据影响当前处理。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function resetModalController() {
  modalStack.value = [];
  modalTriggers.clear();
  detachModalKeydown();
}

// 业务位置：【前端证据室】openEvidenceDetail：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function openEvidenceDetail(item, event, mode = "evidence") {
  selectedEvidence.value = item;
  selectedEvidenceMode.value = mode === "human-review"
    ? "human-review"
    : "evidence";
  openModal("detail", event);
}

// 业务位置：【前端证据室】closeEvidenceDetail：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function closeEvidenceDetail() {
  selectedEvidence.value = null;
  selectedEvidenceMode.value = "evidence";
  closeModal("detail");
}

// 业务位置：【前端证据室】openEvidenceGroup：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function openEvidenceGroup(group, event) {
  expandedEvidenceGroup.value = group;
  openModal("gallery", event);
}

// 业务位置：【前端证据室】closeEvidenceGroup：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function closeEvidenceGroup() {
  expandedEvidenceGroup.value = null;
  closeModal("gallery");
}

const expandedEvidenceItems = computed(() => {
  if (expandedEvidenceGroup.value === "pending") return pendingItems.value;
  if (expandedEvidenceGroup.value === "submitted") return submittedItems.value;
  return [];
});

const expandedEvidenceTitle = computed(() =>
  expandedEvidenceGroup.value === "pending" ? "本批待提交" : "我的原件匣",
);

// 业务位置：【前端证据室】load：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
      if (shouldDiscoverActiveEvidenceRuns.value) {
        await resumeActiveEvidenceRuns(actorSnapshot, caseSnapshot);
      }
    }
  } catch (failure) {
    if (!isCurrentWorkspace(generation, actorSnapshot, caseSnapshot)) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

// 业务位置：【前端证据室】isCurrentWorkspace：判断 页面工作区和业务快照 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function isCurrentWorkspace(generation, actorSnapshot, caseSnapshot) {
  return (
    generation === workspaceGeneration &&
    caseSnapshot === caseId.value &&
    actorSnapshot.id === effectiveActor.value.id &&
    actorSnapshot.role === effectiveActor.value.role
  );
}

// 业务位置：【前端证据室】loadActorScopedMessages：读取 房间消息和对话记录，并依据当前案件、角色和会话权限裁剪成可用输入。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
  const opening = await roomApi.ensureOpening(actorSnapshot, caseSnapshot, "EVIDENCE");
  await consumeEvidenceAgentRun(opening, {
    actorSnapshot,
    caseSnapshot,
  });
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

// 业务位置：【前端证据室】resumeActiveEvidenceRuns：执行 当前可见证据和附件 对应的业务动作，并将结果交给 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
async function resumeActiveEvidenceRuns(
  actorSnapshot = { ...effectiveActor.value },
  caseSnapshot = caseId.value,
) {
  const activeRuns = await loadActiveAgentRuns(
    actorSnapshot,
    caseSnapshot,
    "EVIDENCE",
  );
  await Promise.all((activeRuns || []).map((descriptor) =>
    consumeEvidenceAgentRun(descriptor, {
      actorSnapshot,
      caseSnapshot,
      onFinal: () => refreshWorkspace(),
    }),
  ));
}

// 业务位置：【前端证据室】consumeEvidenceAgentRun：执行 当前可见证据和附件 对应的业务动作，并将结果交给 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
async function consumeEvidenceAgentRun(result, options = {}) {
  const descriptor = extractAgentRunDescriptor(result);
  if (!descriptor) return false;
  const actorSnapshot = options.actorSnapshot || { ...effectiveActor.value };
  const caseSnapshot = options.caseSnapshot || caseId.value;
  streamError.value = "";
  agentState.value = "STREAMING";
  await consumeAgentRun({
    actor: actorSnapshot,
    caseId: caseSnapshot,
    roomType: "EVIDENCE",
    descriptor,
    agentLabel: "证据书记官",
    senderRole: "EVIDENCE_CLERK",
    signal: eventAbortController.signal,
    onFinal: options.onFinal,
    onError: (failure) => {
      streamError.value = failure.message;
    },
  });
  if (agentState.value === "STREAMING") agentState.value = "SPEAKING";
  return true;
}

// 业务位置：【前端证据室】shouldRequestEvidenceOpening：判断 当前可见证据和附件 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function shouldRequestEvidenceOpening(ensureOpening, firstMessages) {
  if (!ensureOpening) return false;
  return (
    firstMessages.length === 0 ||
    containsOnlySupersededOpeningMessages(firstMessages)
  );
}

// 业务位置：【前端证据室】containsOnlySupersededOpeningMessages：切换与 房间消息和对话记录 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function containsOnlySupersededOpeningMessages(firstMessages) {
  return (
    firstMessages.length > 0 &&
    firstMessages.every((message) => isSupersededOpeningMessage(message))
  );
}

// 业务位置：【前端证据室】isSupersededOpeningMessage：判断 房间消息和对话记录 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

// 业务位置：【前端证据室】isDossierSpecificOpeningMessage：判断 案件卷宗 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function isDossierSpecificOpeningMessage(message) {
  const text = message?.message_text || "";
  return (
    message?.sender_role === "CUSTOMER_SERVICE" &&
    message?.message_type === "AGENT_MESSAGE" &&
    text.includes("接待室收敛的案情") &&
    !isSupersededOpeningMessage(message)
  );
}

// 业务位置：【前端证据室】isCurrentEvidenceClerkMessage：判断 当前可见证据和附件 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function isCurrentEvidenceClerkMessage(message) {
  return (
    message?.sender_role === "CUSTOMER_SERVICE" &&
    message?.message_type === "AGENT_MESSAGE" &&
    !isSupersededOpeningMessage(message)
  );
}

// 业务位置：【前端证据室】fetchModelHealth：读取 模型请求和结构化结果，并依据当前案件、角色和会话权限裁剪成可用输入。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
async function fetchModelHealth() {
  if (props.modelHealthLoader) {
    return props.modelHealthLoader();
  }
  const response = await fetch("/agent-api/health/model", {
    headers: { Accept: "application/json" },
    cache: "no-store",
  });
  if (!response.ok) {
    throw new Error(`model health check failed: HTTP ${response.status}`);
  }
  return response.json();
}

// 业务位置：【前端证据室】checkModelConnection：围绕 模型请求和结构化结果 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
async function checkModelConnection() {
  if (modelHealthInFlight) return modelHealthInFlight;
  if (modelConnectionState.value !== "connected") {
    modelConnectionState.value = "checking";
  }
  modelHealthInFlight = (async () => {
    try {
      const payload = await fetchModelHealth();
      const status = String(payload?.model_status || payload?.status || "").toUpperCase();
      modelConnectionState.value =
        status === "CONNECTED" || status === "UP" ? "connected" : "disconnected";
    } catch (_failure) {
      modelConnectionState.value = "disconnected";
    } finally {
      modelHealthInFlight = null;
    }
  })();
  return modelHealthInFlight;
}

// 业务位置：【前端证据室】startModelHealthPolling：启动或关闭与 模型请求和结构化结果 相关的后台任务或订阅，控制运行资源和生命周期。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function startModelHealthPolling() {
  if (modelHealthTimer) return;
  void checkModelConnection();
  modelHealthTimer = window.setInterval(() => {
    void checkModelConnection();
  }, 30000);
}

// 业务位置：【前端证据室】stopModelHealthPolling：启动或关闭与 模型请求和结构化结果 相关的后台任务或订阅，控制运行资源和生命周期。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function stopModelHealthPolling() {
  if (!modelHealthTimer) return;
  window.clearInterval(modelHealthTimer);
  modelHealthTimer = null;
}

// 业务位置：【前端证据室】loadCatalogOrEmpty：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

// 业务位置：【前端证据室】isMissingEvidenceCatalog：判断 当前可见证据和附件 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function isMissingEvidenceCatalog(failure) {
  return ["EVIDENCE_NOT_FOUND", "RESOURCE_NOT_FOUND"].includes(failure?.code);
}

// 业务位置：【前端证据室】refreshWorkspace：重新加载 页面工作区和业务快照，确保页面和下一次 Agent 调用基于最新案件版本。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

// 业务位置：【前端证据室】nextLocalSequence：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function nextLocalSequence() {
  return Math.max(0, ...messages.value.map((message) => message.sequence_no || 0)) + 1;
}

// 业务位置：【前端证据室】upsertMessage：将 房间消息和对话记录 持久化或合并到案件快照，使 核验提示、补证操作和庭审准备 读取到可追溯版本。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function upsertMessage(message) {
  const existingIndex = messages.value.findIndex((item) => item.id === message.id);
  if (existingIndex >= 0) {
    messages.value.splice(existingIndex, 1, message);
    return;
  }
  messages.value.push(message);
}

// 业务位置：【前端证据室】removeMessage：更新 房间消息和对话记录 的消息、缓存或持久记录，避免旧回合数据影响当前处理。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function removeMessage(messageId) {
  messages.value = messages.value.filter((message) => message.id !== messageId);
}

// 业务位置：【前端证据室】hasAgentReplyAfter：判断 当前阶段业务数据 是否满足当前流程分支的进入条件。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function hasAgentReplyAfter(sequenceNo) {
  return messages.value.some(
    (message) =>
      message.message_type === "AGENT_MESSAGE" &&
      (message.sequence_no || 0) > sequenceNo,
  );
}

// 业务位置：【前端证据室】delay：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function delay(ms) {
  if (ms <= 0) return Promise.resolve();
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

// 业务位置：【前端证据室】refreshUntilAgentReply：重新加载 当前阶段业务数据，确保页面和下一次 Agent 调用基于最新案件版本。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
async function refreshUntilAgentReply(afterSequenceNo) {
  await refreshWorkspace();
  if (hasAgentReplyAfter(afterSequenceNo)) return;
  for (let attempt = 0; attempt < props.agentReplyPollAttempts; attempt += 1) {
    await delay(props.agentReplyPollDelayMs);
    await refreshWorkspace();
    if (hasAgentReplyAfter(afterSequenceNo)) return;
  }
}

// 业务位置：【前端证据室】postMessage：执行 房间消息和对话记录 对应的业务动作，并将结果交给 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
    const result = props.messageAction
      ? await props.messageAction(command)
      : await roomApi.postMessage(
          effectiveActor.value,
          caseId.value,
          "EVIDENCE",
          command,
        );
    const descriptor = extractAgentRunDescriptor(result);
    const saved = descriptor ? resultRoomMessage(result) : result;
    if (saved && typeof saved === "object") {
      removeMessage(pendingId);
      upsertMessage(saved);
    }
    if (descriptor) {
      await consumeEvidenceAgentRun(result, {
        onFinal: () => refreshWorkspace(),
      });
    } else if (!props.messageAction) {
      await refreshUntilAgentReply(saved?.sequence_no || pendingMessage.sequence_no);
    }
    agentState.value = "SPEAKING";
  } catch (failure) {
    removeMessage(pendingId);
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

// 业务位置：【前端证据室】startEventStream：启动或关闭与 Agent 流事件 相关的后台任务或订阅，控制运行资源和生命周期。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

// 业务位置：【前端证据室】uploadFiles：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
        modelProcessingAuthorized: true,
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

// 业务位置：【前端证据室】submitPendingBatch：执行 当前阶段业务数据 对应的业务动作，并将结果交给 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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
    const descriptor = extractAgentRunDescriptor(result);
    const roomMessage = resultRoomMessage(result);
    if (roomMessage && typeof roomMessage === "object") {
      upsertMessage(roomMessage);
    }
    if (descriptor) {
      await consumeEvidenceAgentRun(result, {
        onFinal: () => refreshWorkspace(),
      });
    } else {
      await refreshUntilAgentReply(
        roomMessage?.sequence_no || nextLocalSequence() - 1,
      );
    }
    agentState.value = "SPEAKING";
  } catch (failure) {
    error.value = failure.message;
    agentState.value = "ERROR";
  } finally {
    submittingBatch.value = false;
  }
}

// 业务位置：【前端证据室】deletePendingEvidence：围绕 当前可见证据和附件 计算本模块需要的派生信息，使其能够从 可见证据、事实矩阵和证据 Agent 流 正确进入 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

// 业务位置：【前端证据室】completeEvidence：执行 当前可见证据和附件 对应的业务动作，并将结果交给 核验提示、补证操作和庭审准备。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
async function completeEvidence(event) {
  if (!canCompleteEvidenceLocally.value) {
    evidenceGateError.value = "发起争议方需先正式提交至少 1 份相关证据，当前证据栏为空，暂不能进入下一步。";
    agentState.value = "ERROR";
    openModal("gate", event);
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

// 业务位置：【前端证据室】mergeCompletionResult：将 阶段处理结果或草案 持久化或合并到案件快照，使 核验提示、补证操作和庭审准备 读取到可追溯版本。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
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

// 业务位置：【前端证据室】dismissEvidenceGate：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function dismissEvidenceGate() {
  evidenceGateError.value = "";
  agentState.value = "LISTENING";
  closeModal("gate");
}

// 业务位置：【前端证据室】dismissStreamError：切换与 Agent 流事件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function dismissStreamError() {
  const previous = streamError.value;
  streamError.value = "";
  if (error.value === previous) error.value = "";
  if (agentState.value === "ERROR") agentState.value = "LISTENING";
}

// 业务位置：【前端证据室】enterHearing：切换与 庭审轮次和法官发言 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：可见证据、事实矩阵和证据 Agent 流。下游：核验提示、补证操作和庭审准备。边界：只展示当前角色可见证据。
function enterHearing() {
  return router.push(`/disputes/${caseId.value}/hearing`);
}

onMounted(async () => {
  startModelHealthPolling();
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
  clearAgentStreams({ caseId: caseId.value, roomType: "EVIDENCE" });
  workspaceGeneration += 1;
  const generation = workspaceGeneration;
  error.value = "";
  streamError.value = "";
  agentState.value = "THINKING";
  catalog.value = null;
  completion.value = null;
  selectedEvidence.value = null;
  selectedEvidenceMode.value = "evidence";
  expandedEvidenceGroup.value = null;
  evidenceGateError.value = "";
  resetModalController();
  messages.value = [];
  if (eventStreamActive) {
    startEventStream();
  }
  try {
    await refreshWorkspace({ generation, ensureOpening: props.initialMessages === null });
    if (shouldDiscoverActiveEvidenceRuns.value) {
      await resumeActiveEvidenceRuns();
    }
    agentState.value = "LISTENING";
  } catch (failure) {
    if (generation !== workspaceGeneration) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  }
});
onBeforeUnmount(() => {
  eventAbortController.abort();
  clearAgentStreams({ caseId: caseId.value, roomType: "EVIDENCE" });
  stopModelHealthPolling();
  resetModalController();
});
</script>

<template>
  <RoomShell
    eyebrow="EVIDENCE GARDEN"
    title="证据书记官室"
    subtitle="证据核验"
    subtitle-description="请围绕待核验事实提交材料，证据书记官会说明关联性、完整性和人工复核要求。"
    :case-id="caseId"
    :connection-state="connectionState"
    :show-boundary="false"
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
        <div
          class="evidence-room__case-note"
          :data-status="evidenceWorkStatusCopy.tone"
          data-evidence-work-status
        >
          <i class="evidence-room__status-orb" aria-hidden="true" />
          <div class="evidence-room__status-copy">
            <span>{{ evidenceWorkStatusCopy.eyebrow }}</span>
            <h2>{{ evidenceWorkStatusCopy.title }}</h2>
            <p>{{ evidenceWorkStatusCopy.description }}</p>
          </div>
          <div class="evidence-room__status-meta">
            <small :data-model-state="evidenceStreamingRuns.length ? 'connected' : modelConnectionState">
              {{ modelConnectionLabel }}
            </small>
          </div>
        </div>
        <div class="evidence-room__conversation-frame">
          <ConversationStream
            :messages="displayedMessages"
            :streaming-runs="evidenceStreamingRuns"
            :disabled="evidenceComposerDisabled"
            :empty-text="evidenceConversationEmptyText"
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
            <p>点击任意证据卡片查看核验结果与人工复核信息。</p>
          </div>
          <span class="evidence-board__badge">
            {{ isMerchant ? "商家证据方" : "用户证据方" }}
          </span>
        </header>

        <div class="evidence-board__cards" data-evidence-list-scroll>
          <section class="evidence-uploader">
            <div class="evidence-uploader__illustration" aria-hidden="true">
              <span>📎</span><span>🖼️</span><span>🎞️</span>
            </div>
            <div>
              <span class="evidence-kicker">MULTIMODAL DESK</span>
              <strong>上传图片、视频、Markdown 或文档</strong>
              <small>解析完成后先进入待提交区，确认后再交给书记官。</small>
            </div>
            <label class="evidence-uploader__button">
              {{ uploading ? "核验中…" : "选择材料" }}
              <input
                ref="fileInput"
                type="file"
                multiple
                :disabled="uploading || evidenceStreamingRuns.length > 0"
                @change="uploadFiles"
              />
            </label>
          </section>

          <section class="evidence-library evidence-library--pending" data-evidence-pending>
            <header>
              <div>
                <span class="evidence-kicker">STAGING</span>
                <h3>本批待提交</h3>
              </div>
              <span class="privacy-seal">{{ pendingItems.length }} 个待提交</span>
            </header>
            <div class="evidence-card-strip" data-evidence-horizontal-strip>
              <p v-if="!pendingItems.length" class="evidence-empty">选择材料后会在这里生成预览。</p>
              <article
                v-for="item in pendingItems"
                :key="evidenceId(item)"
                class="evidence-card evidence-card--compact evidence-card--pending"
                data-evidence-card
                tabindex="0"
                @click="openEvidenceDetail(item, $event)"
                @keydown.enter.self.prevent="openEvidenceDetail(item, $event)"
                @keydown.space.self.prevent="openEvidenceDetail(item, $event)"
              >
                <button
                  type="button"
                  class="evidence-card__remove"
                  data-delete-pending-evidence
                  :disabled="isDeletingEvidence(item)"
                  @click.stop="deletePendingEvidence(item)"
                >×</button>
                <span class="evidence-card__preview">
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
                    <span class="evidence-file-icon__badge" data-file-badge>{{ evidenceFileIcon(item).badge }}</span>
                  </span>
                </span>
                <span class="evidence-card__main">
                  <strong>{{ evidenceTypeLabels[item.evidence_type] || item.evidence_type }}</strong>
                  <small class="evidence-card__filename" data-evidence-filename :title="evidenceOriginalFilename(item) || evidenceId(item)">
                    {{ evidenceOriginalFilename(item) || evidenceId(item) }}
                  </small>
                </span>
                <span class="evidence-card__labels" data-evidence-status-row>
                  <em>{{ evidenceOwnerLabel(item) }}</em>
                  <em>待提交</em>
                </span>
              </article>
            </div>
            <div v-if="pendingItems.length" class="evidence-library__actions">
              <button
                type="button"
                data-submit-evidence-batch
                :disabled="submittingBatch || evidenceStreamingRuns.length > 0"
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
                <h3>已提交证据</h3>
              </div>
              <span class="privacy-seal">{{ submittedItems.length }} 项</span>
            </header>
            <div class="evidence-card-strip" data-evidence-horizontal-strip>
              <p v-if="!submittedItems.length" class="evidence-empty">暂无已提交证据。</p>
              <button
                v-for="item in submittedItems"
                :key="evidenceId(item)"
                type="button"
                class="evidence-card evidence-card--compact evidence-card--submitted"
                data-evidence-card
                @click="openEvidenceDetail(item, $event)"
              >
                <span class="evidence-card__preview">
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
                    <span class="evidence-file-icon__badge" data-file-badge>{{ evidenceFileIcon(item).badge }}</span>
                  </span>
                </span>
                <span class="evidence-card__main">
                  <strong>{{ evidenceTypeLabels[item.evidence_type] || item.evidence_type }}</strong>
                  <small class="evidence-card__filename" data-evidence-filename :title="evidenceOriginalFilename(item) || evidenceId(item)">
                    {{ evidenceOriginalFilename(item) || evidenceId(item) }}
                  </small>
                </span>
                <span class="evidence-card__labels" data-evidence-status-row>
                  <em>{{ evidenceOwnerLabel(item) }}</em>
                  <em>待人工复核</em>
                </span>
              </button>
            </div>
          </section>

          <section
            class="evidence-library evidence-library--human-review"
            data-human-review-queue
          >
            <header>
              <div>
                <span class="evidence-kicker">HUMAN REVIEW</span>
                <h3>待人工审核</h3>
              </div>
              <span class="human-review-seal">{{ humanReviewItems.length }} 项待核查</span>
            </header>
            <div class="human-review-list evidence-card-strip" data-human-review-list data-evidence-vertical-strip>
              <p v-if="!humanReviewItems.length" class="evidence-empty">当前没有需要人工复核的证据。</p>
              <button
                v-for="item in humanReviewItems"
                :key="`human-review-${evidenceId(item)}`"
                type="button"
                class="human-review-card evidence-card evidence-card--compact"
                data-human-review-card
                data-evidence-card
                @click="openEvidenceDetail(item, $event, 'human-review')"
              >
                <span class="evidence-card__preview">
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
                    <span class="evidence-file-icon__badge" data-file-badge>{{ evidenceFileIcon(item).badge }}</span>
                  </span>
                </span>
                <span class="evidence-card__main">
                  <strong :title="evidenceOriginalFilename(item) || evidenceId(item)">{{ evidenceOriginalFilename(item) || evidenceId(item) }}</strong>
                  <small>点击查看人工审核要求</small>
                </span>
                <span class="evidence-card__labels">
                  <em>{{ evidenceOwnerLabel(item) }}</em>
                  <em>人工审核任务</em>
                </span>
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
            :disabled="completing || evidenceStreamingRuns.length > 0"
            @click="completeEvidence"
          >
            {{ completing ? "正在封入证据袋…" : "我方举证完成" }}
          </button>
        </footer>
        <p v-if="error" class="evidence-error" role="alert">{{ error }}</p>
      </section>
    </div>

    <AgentStreamErrorDialog
      :message="streamError"
      @dismiss="dismissStreamError"
    />

    <div
      v-if="evidenceGateError"
      ref="evidenceGateModal"
      class="evidence-modal"
      data-evidence-gate-modal
      :data-modal-depth="modalDepth('gate')"
      :style="{ zIndex: modalDepth('gate') }"
      role="dialog"
      :aria-modal="isTopModal('gate') ? 'true' : 'false'"
      :aria-hidden="isModalCovered('gate') ? 'true' : undefined"
      :inert="isModalCovered('gate') ? true : undefined"
      aria-labelledby="evidence-gate-title"
    >
      <section class="evidence-modal__panel evidence-modal__panel--notice">
        <span class="evidence-kicker">EVIDENCE REQUIRED</span>
        <h2 id="evidence-gate-title">暂不能完成举证</h2>
        <p>{{ evidenceGateError }}</p>
        <button
          type="button"
          data-dismiss-evidence-gate
          data-modal-initial-focus
          @click="dismissEvidenceGate"
        >
          我知道了
        </button>
      </section>
    </div>

    <div
      v-if="selectedEvidence"
      ref="evidenceDetailModal"
      class="evidence-modal"
      data-evidence-detail-modal
      :data-modal-depth="modalDepth('detail')"
      :style="{ zIndex: modalDepth('detail') }"
      role="dialog"
      :aria-modal="isTopModal('detail') ? 'true' : 'false'"
      :aria-hidden="isModalCovered('detail') ? 'true' : undefined"
      :inert="isModalCovered('detail') ? true : undefined"
      :aria-label="selectedEvidenceMode === 'human-review' ? '人工审核详情' : '证据详情'"
      @click.self="closeEvidenceDetail"
    >
      <section
        class="evidence-modal__panel evidence-modal__panel--detail"
        :class="{ 'evidence-modal__panel--human-review': selectedEvidenceMode === 'human-review' }"
        :data-detail-mode="selectedEvidenceMode"
      >
        <header class="evidence-modal__detail-header">
          <div class="evidence-modal__identity">
            <span
              class="evidence-card__icon evidence-file-icon evidence-file-icon--large"
              :class="evidenceFileIconStatusClass(selectedEvidence)"
              :data-file-kind="evidenceFileIcon(selectedEvidence).kind"
              :aria-label="evidenceFileIcon(selectedEvidence).label"
            >
              <span class="evidence-file-icon__body" aria-hidden="true">
                <span class="evidence-file-icon__landscape"></span>
                <span class="evidence-file-icon__play"></span>
                <span class="evidence-file-icon__lines"></span>
              </span>
              <span class="evidence-file-icon__badge">{{ evidenceFileIcon(selectedEvidence).badge }}</span>
            </span>
            <div>
              <span class="evidence-kicker">{{ selectedEvidenceMode === "human-review" ? "人工审核详情" : "证据详情" }}</span>
              <h2>{{ evidenceOriginalFilename(selectedEvidence) || evidenceId(selectedEvidence) }}</h2>
              <p>{{ evidenceTypeLabels[selectedEvidence.evidence_type] || selectedEvidence.evidence_type || "其他材料" }}</p>
            </div>
          </div>
          <button
            type="button"
            class="evidence-modal__close"
            data-close-evidence-modal
            data-modal-initial-focus
            :aria-label="selectedEvidenceMode === 'human-review' ? '关闭人工审核详情' : '关闭证据详情'"
            @click="closeEvidenceDetail"
          >
            ×
          </button>
        </header>
        <div class="evidence-modal__facts">
          <span><small>提交方</small><strong>{{ evidenceOwnerLabel(selectedEvidence) }}</strong></span>
          <span><small>核验状态</small><strong>{{ statusLabels[evidenceVerificationStatus(selectedEvidence)] || evidenceVerificationStatus(selectedEvidence) || "待核验" }}</strong></span>
          <span><small>核验把握</small><strong>{{ evidenceConfidenceCopy(selectedEvidence) }}</strong></span>
          <span><small>提交状态</small><strong>{{ evidenceSubmissionStatusLabel(selectedEvidence) }}</strong></span>
        </div>
        <section class="evidence-modal__assessment" data-evidence-detail-assessment>
          <div class="evidence-modal__section-title">
            <strong>多维核验结果</strong>
            <span>AI 初步核验</span>
          </div>
          <div class="human-review-metrics">
            <span><small>真实性</small><strong>{{ evidenceMetricCopy(selectedEvidence, "authenticity_score", "authenticityScore") }}</strong></span>
            <span><small>关联性</small><strong>{{ evidenceMetricCopy(selectedEvidence, "relevance_score", "relevanceScore") }}</strong></span>
            <span><small>完整性</small><strong>{{ evidenceMetricCopy(selectedEvidence, "completeness_score", "completenessScore") }}</strong></span>
            <span><small>核验把握</small><strong>{{ evidenceConfidenceScore(selectedEvidence) === null ? "待评估" : `${evidenceConfidenceScore(selectedEvidence)}%` }}</strong></span>
          </div>
        </section>
        <article
          v-if="selectedEvidenceMode === 'human-review' && evidenceRequiresHumanReview(selectedEvidence)"
          class="evidence-modal__human-review"
          data-evidence-detail-human-review
        >
          <div class="evidence-modal__section-title">
            <strong>待人工审核</strong>
            <span>平台复核</span>
          </div>
          <div class="evidence-modal__review-scroll">
            <section>
              <b>触发原因</b>
              <ul v-if="evidenceHumanReviewReasons(selectedEvidence).length">
                <li v-for="reason in evidenceHumanReviewReasons(selectedEvidence)" :key="reason">{{ humanReviewReasonLabel(reason) }}</li>
              </ul>
              <p v-else>模型将该材料标记为需要人工审核。</p>
            </section>
            <section>
              <b>模型限制</b>
              <ul v-if="evidenceLimitations(selectedEvidence).length">
                <li v-for="limitation in evidenceLimitations(selectedEvidence)" :key="limitation">{{ limitation }}</li>
              </ul>
              <p v-else>暂无具体限制说明，请以原始材料为准。</p>
            </section>
            <section>
              <b>审核指引</b>
              <ul v-if="evidenceHumanReviewInstructions(selectedEvidence).length">
                <li v-for="instruction in evidenceHumanReviewInstructions(selectedEvidence)" :key="instruction">{{ instruction }}</li>
              </ul>
              <p v-else>请结合原件、元数据和相关业务记录进行交叉核验。</p>
            </section>
          </div>
        </article>
        <article v-if="evidenceFeedback(selectedEvidence)" class="evidence-modal__feedback">
          <div class="evidence-modal__section-title">
            <strong>书记官核验反馈</strong>
            <span>核验说明</span>
          </div>
          <p>{{ evidenceFeedbackDisplay(selectedEvidence) }}</p>
        </article>
        <footer class="evidence-modal__actions">
          <span>{{ selectedEvidenceMode === "human-review" ? "该材料需要结合原件与业务记录完成人工确认。" : "AI 核验结果仅供审核参考，请结合原始材料判断。" }}</span>
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
        </footer>
      </section>
    </div>

    <div
      v-if="expandedEvidenceGroup"
      ref="evidenceGalleryModal"
      class="evidence-modal"
      data-evidence-gallery-modal
      :data-modal-depth="modalDepth('gallery')"
      :style="{ zIndex: modalDepth('gallery') }"
      role="dialog"
      :aria-modal="isTopModal('gallery') ? 'true' : 'false'"
      :aria-hidden="isModalCovered('gallery') ? 'true' : undefined"
      :inert="isModalCovered('gallery') ? true : undefined"
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
          <button
            type="button"
            data-close-evidence-gallery
            data-modal-initial-focus
            @click="closeEvidenceGroup"
          >
            关闭
          </button>
        </header>
        <div class="evidence-gallery-grid">
          <article
            v-for="item in expandedEvidenceItems"
            :key="evidenceId(item)"
            class="evidence-gallery-card"
            data-evidence-gallery-card
            tabindex="0"
            @click="openEvidenceDetail(item, $event)"
            @keydown.enter.self.prevent="openEvidenceDetail(item, $event)"
            @keydown.space.self.prevent="openEvidenceDetail(item, $event)"
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
  box-sizing: border-box;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  height: 92px;
  padding: 15px 16px 18px;
  margin: 0 0 14px;
  overflow: hidden;
  background:
    radial-gradient(circle at 20% 15%, rgba(255, 255, 255, .95), transparent 34%),
    linear-gradient(135deg, #f8fbff, #f4f7ff);
  border: 1px solid #dce8f4;
  border-radius: 18px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .92);
}

.evidence-room__case-note span,
.evidence-kicker {
  color: #7186aa;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}

.evidence-room__status-orb {
  position: relative;
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 14px;
  background: linear-gradient(135deg, #8ca2ff, #77dfb7);
  box-shadow: 0 10px 24px rgba(96, 122, 180, .22);
}

.evidence-room__status-orb::before,
.evidence-room__status-orb::after {
  content: "";
  position: absolute;
  border-radius: 999px;
}

.evidence-room__status-orb::before {
  width: 11px;
  height: 11px;
  background: #fff;
}

.evidence-room__status-orb::after {
  inset: -5px;
  border: 1px solid rgba(126, 151, 232, .38);
  animation: evidence-status-pulse 1.55s ease-out infinite;
}

.evidence-room__status-copy {
  min-width: 0;
}

.evidence-room__case-note h2 {
  margin: 3px 0 2px;
  color: #34435c;
  font-size: 17px;
  line-height: 1.22;
}

.evidence-room__case-note p {
  display: -webkit-box;
  margin: 0;
  overflow: hidden;
  color: #6f7d92;
  font-size: 12px;
  line-height: 1.42;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.evidence-room__status-meta {
  display: grid;
  min-width: 96px;
  justify-items: end;
  gap: 4px;
}

.evidence-room__status-meta small {
  padding: 3px 8px;
  color: #71809a;
  background: rgba(255, 255, 255, .72);
  border: 1px solid #dfe8f4;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
}

.evidence-room__status-meta small[data-model-state="connected"] {
  color: #2f8569;
  background: rgba(229, 250, 240, .82);
  border-color: rgba(106, 211, 169, .48);
}

.evidence-room__status-meta small[data-model-state="checking"] {
  color: #6b6f9a;
  background: rgba(241, 244, 255, .86);
  border-color: rgba(163, 174, 240, .5);
}

.evidence-room__status-meta small[data-model-state="disconnected"] {
  color: #b24b5d;
  background: rgba(255, 238, 240, .9);
  border-color: rgba(244, 143, 156, .55);
}

.evidence-room__case-note[data-status="working"] .evidence-room__status-orb {
  background: linear-gradient(135deg, #a98cf5, #79b9ff);
}

.evidence-room__case-note[data-status="streaming"] .evidence-room__status-orb {
  background: linear-gradient(135deg, #7f8df1, #62c4ee);
}

.evidence-room__case-note[data-status="ready"] .evidence-room__status-orb {
  background: linear-gradient(135deg, #64d8a4, #70c7ff);
}

.evidence-room__case-note[data-status="handoff"] .evidence-room__status-orb {
  background: linear-gradient(135deg, #74a7ff, #b7c4da);
}

.evidence-room__case-note[data-status="error"] .evidence-room__status-orb {
  background: linear-gradient(135deg, #ff7f8d, #ffbd8a);
}

.evidence-room__case-note[data-status="ready"] .evidence-room__status-orb::after,
.evidence-room__case-note[data-status="handoff"] .evidence-room__status-orb::after,
.evidence-room__case-note[data-status="error"] .evidence-room__status-orb::after {
  animation: none;
  opacity: .35;
}

@keyframes evidence-status-pulse {
  0% {
    opacity: .85;
    transform: scale(.86);
  }
  100% {
    opacity: 0;
    transform: scale(1.28);
  }
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
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
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

.evidence-library--human-review {
  background: linear-gradient(160deg, #fff2e9, #fffaf6 48%, #fff);
  border-color: #f1d1bd;
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

.human-review-seal {
  height: max-content;
  padding: 6px 9px;
  color: #985a3f;
  background: #fff8f2;
  border: 1px solid #efd1bf;
  border-radius: 999px;
  font-size: 11px;
  white-space: nowrap;
}

.human-review-intro {
  margin: 0 0 9px;
  color: #7d6b62;
  font-size: 12px;
  line-height: 1.5;
}

.human-review-list {
  display: grid;
  min-width: 0;
  gap: 10px;
}

.human-review-card {
  min-width: 0;
  padding: 12px;
  background: #ffffffeb;
  border: 1px solid #ecd8ca;
  border-radius: 16px;
  box-shadow: 0 10px 24px #7b503a0d;
}

.human-review-card .human-review-card__header {
  align-items: center;
  margin-bottom: 10px;
}

.human-review-card__header > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.human-review-card__header strong {
  overflow: hidden;
  color: #4c403a;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.human-review-card__header small {
  color: #88756b;
  font-size: 11px;
}

.human-review-card__header > span {
  flex: 0 0 auto;
  padding: 5px 7px;
  color: #9b5b3e;
  background: #fff0e5;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
  white-space: nowrap;
}

.human-review-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  min-width: 0;
  gap: 6px;
}

.human-review-metrics > span {
  display: grid;
  min-width: 0;
  gap: 3px;
  padding: 7px 6px;
  text-align: center;
  background: #f8f4f1;
  border-radius: 10px;
}

.human-review-metrics small {
  overflow: hidden;
  color: #8a786e;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.human-review-metrics strong {
  color: #54433b;
  font-size: 12px;
}

.human-review-card__body {
  display: grid;
  min-width: 0;
  gap: 9px;
  margin-top: 10px;
}

.human-review-card__body section {
  min-width: 0;
}

.human-review-card__body section > strong {
  color: #574841;
  font-size: 11px;
}

.human-review-card__body ul,
.human-review-card__body p,
.evidence-modal__human-review ul {
  margin: 4px 0 0;
  padding-left: 18px;
  color: #75675f;
  font-size: 11px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.human-review-card__body p {
  padding-left: 0;
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
  min-width: 0;
}

.evidence-modal__panel header > div {
  min-width: 0;
}

.evidence-modal__panel h2 {
  margin: 5px 0;
  color: #2f3f58;
  font-size: 20px;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-modal__panel p {
  min-width: 0;
  margin: 0;
  color: #66758d;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-modal__panel button,
.evidence-modal__link {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
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

.evidence-modal__facts span {
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-modal__assessment {
  display: grid;
  min-width: 0;
  gap: 9px;
  padding: 12px;
  background: #ffffffd9;
  border: 1px solid #e2ebf6;
  border-radius: 14px;
}

.evidence-modal__assessment > strong {
  color: #33435c;
}

.evidence-modal__assessment > p {
  font-size: 12px;
}

.evidence-modal__human-review {
  border-color: #efd1bf !important;
  background: #fff8f2 !important;
}

.evidence-modal__review-scroll {
  display: grid;
  max-height: 230px;
  min-width: 0;
  gap: 12px;
  padding-right: 5px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.evidence-modal__review-scroll section {
  min-width: 0;
}

.evidence-modal__review-scroll b {
  color: #5a4941;
  font-size: 12px;
}

.evidence-modal__human-review ul,
.evidence-modal__human-review p {
  margin-top: 5px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.evidence-modal__panel article strong {
  display: block;
  margin-bottom: 6px;
  color: #33435c;
}

.evidence-modal__panel--detail {
  width: min(760px, 100%);
  max-height: min(680px, calc(100vh - 48px));
  gap: 10px;
  padding: 18px;
  background: #f7f9fc;
  border-color: #dfe7f1;
  border-radius: 24px;
}

.evidence-modal__detail-header {
  align-items: center;
  padding: 12px 14px;
  background: linear-gradient(135deg, #ffffff 20%, #f0f6ff 100%);
  border: 1px solid #e2eaf4;
  border-radius: 18px;
}

.evidence-modal__panel--human-review .evidence-modal__detail-header {
  background: linear-gradient(135deg, #fffdfb 15%, #fff2e7 100%);
  border-color: #efd7c5;
}

.evidence-modal__panel--human-review .evidence-modal__identity .evidence-kicker {
  color: #a76843;
}

.evidence-modal__panel--human-review .evidence-modal__section-title > span {
  color: #9b5f3c;
  background: #fff0e4;
}

.evidence-modal__panel--human-review .evidence-modal__section-title > strong {
  color: #684633;
}

.evidence-modal__identity {
  display: flex;
  align-items: center;
  gap: 14px;
}

.evidence-modal__identity > div {
  min-width: 0;
}

.evidence-modal__identity .evidence-file-icon--large {
  flex: 0 0 auto;
  margin: 0;
}

.evidence-modal__identity h2 {
  margin: 3px 0 2px;
  font-size: 19px;
  line-height: 1.3;
}

.evidence-modal__identity p {
  color: #8794a7;
  font-size: 12px;
  font-weight: 700;
}

.evidence-modal__panel .evidence-modal__close {
  width: 36px;
  min-width: 36px;
  min-height: 36px;
  padding: 0;
  color: #75849a;
  background: #edf3f9;
  border-radius: 50%;
  font-size: 22px;
  font-weight: 500;
  line-height: 1;
}

.evidence-modal__panel .evidence-modal__close:hover {
  color: #40516a;
  background: #e3ebf5;
}

.evidence-modal__panel--detail .evidence-modal__facts {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0;
  padding: 5px;
  background: #edf2f7;
  border: 1px solid #e1e9f2;
  border-radius: 15px;
}

.evidence-modal__panel--detail .evidence-modal__facts span {
  display: grid;
  gap: 5px;
  padding: 8px 13px;
  background: transparent;
  border: 0;
  border-radius: 0;
  white-space: normal;
}

.evidence-modal__panel--detail .evidence-modal__facts span + span {
  border-left: 1px solid #d8e1eb;
}

.evidence-modal__panel--human-review .evidence-modal__facts {
  background: #f7ede5;
  border-color: #ead7c8;
}

.evidence-modal__panel--human-review .evidence-modal__facts span + span {
  border-left-color: #e3cfc0;
}

.evidence-modal__facts small {
  color: #8a98aa;
  font-size: 10px;
  font-weight: 700;
}

.evidence-modal__facts strong {
  overflow: hidden;
  color: #3e4f68;
  font-size: 13px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-modal__panel--detail .evidence-modal__assessment {
  padding: 14px;
  background: #fff;
  border-color: #e2ebf5;
  border-radius: 15px;
}

.evidence-modal__panel--human-review .evidence-modal__assessment {
  background: #fffaf6;
  border-color: #efd9c9;
}

.evidence-modal__section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.evidence-modal__section-title > strong {
  margin: 0 !important;
  color: #33445d;
  font-size: 14px;
}

.evidence-modal__section-title > span {
  padding: 4px 8px;
  color: #6174c8;
  background: #eef1ff;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
}

.evidence-modal__panel--detail .human-review-metrics > span {
  padding: 8px;
  background: #f4f7fb;
  border: 0;
  border-radius: 10px;
}

.evidence-modal__panel--detail .human-review-metrics small {
  color: #8996a8;
}

.evidence-modal__panel--detail .human-review-metrics strong {
  margin: 0;
  color: #43536b;
  font-size: 13px;
}

.evidence-modal__panel--human-review .human-review-metrics > span {
  background: #f9eee6;
}

.evidence-modal__panel--human-review .human-review-metrics small {
  color: #9b7862;
}

.evidence-modal__panel--human-review .human-review-metrics strong {
  color: #704b36;
}

.evidence-modal__panel--detail .evidence-modal__human-review {
  display: grid;
  gap: 9px;
  padding: 14px;
  border-color: #f0d9c8 !important;
  background: linear-gradient(135deg, #fffaf5, #fffdfb) !important;
  border-radius: 15px;
}

.evidence-modal__panel--detail .evidence-modal__review-scroll {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  max-height: none;
  gap: 0;
  padding: 0;
  overflow: visible;
  scrollbar-gutter: auto;
}

.evidence-modal__panel--detail .evidence-modal__review-scroll section {
  padding: 2px 14px 0;
}

.evidence-modal__panel--detail .evidence-modal__review-scroll section:first-child {
  padding-left: 0;
}

.evidence-modal__panel--detail .evidence-modal__review-scroll section:last-child {
  padding-right: 0;
}

.evidence-modal__panel--detail .evidence-modal__review-scroll section + section {
  border-left: 1px solid #ecdccc;
}

.evidence-modal__panel--detail .evidence-modal__human-review ul {
  margin-bottom: 0;
  padding-left: 16px;
  color: #6f625b;
  font-size: 11px;
  line-height: 1.45;
}

.evidence-modal__feedback {
  position: relative;
  display: grid;
  gap: 9px;
  padding: 14px 15px 14px 18px !important;
  overflow: hidden;
  background: linear-gradient(135deg, #fff, #f8fbff) !important;
  border-color: #e2ebf5 !important;
  border-radius: 15px !important;
}

.evidence-modal__feedback::before {
  position: absolute;
  inset: 12px auto 12px 0;
  width: 3px;
  content: "";
  background: #8c9de3;
  border-radius: 0 999px 999px 0;
}

.evidence-modal__feedback p {
  color: #5f6f86;
  font-size: 13px;
  line-height: 1.55;
}

.evidence-modal__panel--human-review .evidence-modal__feedback {
  background: linear-gradient(135deg, #f8ede4, #f5e5d9) !important;
  border-color: #e8cbb7 !important;
}

.evidence-modal__panel--human-review .evidence-modal__feedback::before {
  background: #d28b5e;
}

.evidence-modal__panel--human-review .evidence-modal__feedback p {
  color: #6d4d3a;
}

.evidence-modal__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 2px 2px 0;
}

.evidence-modal__actions > span {
  min-width: 0;
  color: #8a97a8;
  font-size: 11px;
  line-height: 1.5;
}

.evidence-modal__actions .evidence-modal__link {
  flex: 0 0 auto;
  min-height: 40px;
  padding-inline: 16px;
  color: #fff;
  background: linear-gradient(135deg, #7886df, #6070cc);
  border-radius: 12px;
  box-shadow: 0 8px 18px #6676cf2b;
}

.evidence-modal__panel--human-review .evidence-modal__actions > span {
  color: #987762;
}

.evidence-modal__panel--human-review .evidence-modal__actions .evidence-modal__link {
  background: linear-gradient(135deg, #d99365, #bd7448);
  box-shadow: 0 8px 18px #bd74482b;
}

@container room-workspace (max-width: 1059px) {
  .evidence-room { grid-template-columns: 1fr; }
}

@media (max-width: 620px) {
  .evidence-modal__panel--detail .evidence-modal__facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .evidence-modal__panel--detail .evidence-modal__facts span:nth-child(3) {
    border-left: 0;
  }

  .evidence-modal__panel--detail .evidence-modal__review-scroll {
    grid-template-columns: 1fr;
  }

  .evidence-modal__panel--detail .evidence-modal__review-scroll section {
    padding: 8px 0;
  }

  .evidence-modal__panel--detail .evidence-modal__review-scroll section + section {
    border-top: 1px solid #ecdccc;
    border-left: 0;
  }

  .evidence-modal__actions {
    align-items: stretch;
    flex-direction: column;
  }

  .evidence-modal__actions .evidence-modal__link {
    width: 100%;
  }

  .human-review-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .evidence-uploader {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px;
  }

  .evidence-uploader__illustration,
  .evidence-uploader .evidence-kicker {
    display: none;
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
    margin: 7px 0 8px;
    font-size: clamp(32px, 5vw, 56px);
    line-height: 1.05;
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
    padding: 10px;
  }

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

/* Fixed evidence board: four stable cards with horizontal evidence rails. */
.evidence-board {
  grid-template-rows: 76px minmax(0, 1fr) 60px;
}

.evidence-board__cards {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-columns: minmax(0, 1.12fr) minmax(190px, .88fr);
  grid-template-rows: 86px minmax(0, 1.12fr) minmax(0, .88fr);
  gap: 10px;
  overflow: hidden;
}

.evidence-board__cards > .evidence-uploader {
  grid-column: 1 / -1;
  grid-row: 1;
}

.evidence-board__cards > .evidence-library--pending {
  grid-column: 1;
  grid-row: 2;
}

.evidence-board__cards > .evidence-library--pending .evidence-card-strip {
  min-height: 125px;
}

.evidence-board__cards > .evidence-library--private {
  grid-column: 1;
  grid-row: 3;
}

.evidence-board__cards > .evidence-library--human-review {
  grid-column: 2;
  grid-row: 2 / span 2;
}

.evidence-board__cards .evidence-library {
  display: grid;
  height: 100%;
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  grid-template-rows: auto minmax(0, 1fr) auto;
  padding: 10px;
  overflow: hidden;
}

.evidence-board__cards .evidence-library > header {
  align-items: center;
  margin-bottom: 7px;
}

.evidence-card-strip {
  display: flex;
  min-width: 0;
  min-height: 0;
  align-items: flex-start;
  gap: 8px;
  padding: 2px 2px 7px;
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-x: contain;
  scrollbar-color: #b9c9dd transparent;
  scrollbar-width: thin;
  scroll-snap-type: x proximity;
}

.evidence-card-strip::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.evidence-card-strip::-webkit-scrollbar-thumb { background: #b9c9dd; border-radius: 999px; }
.evidence-card-strip::-webkit-scrollbar-track { background: transparent; }

.evidence-card.evidence-card--compact {
  display: grid;
  flex: 0 0 168px;
  width: 168px;
  height: 116px;
  min-width: 168px;
  box-sizing: border-box;
  grid-template-columns: 56px minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr) auto;
  grid-template-areas:
    "preview main"
    "labels labels";
  align-items: center;
  gap: 7px 9px;
  margin: 0;
  padding: 9px;
  overflow: hidden;
  scroll-snap-align: start;
}

.evidence-card.evidence-card--compact.evidence-card--pending { padding-right: 30px; }

.evidence-card.evidence-card--compact.evidence-card--submitted {
  background: linear-gradient(145deg, #ffffff, #f7fbff);
  border-color: #dce8f3;
  box-shadow: 0 8px 18px #456b9810;
}

.evidence-card--submitted .evidence-card__main > strong {
  color: #385570;
}

.evidence-card--submitted .evidence-card__main > small {
  color: #71889d;
}

.evidence-card--submitted .evidence-card__labels em {
  color: #57738c;
  background: #eaf2f8;
}

.evidence-card--submitted .evidence-card__labels em:last-child {
  color: #426786;
  background: #dcebf6;
}

.evidence-card.evidence-card--compact.evidence-card--submitted::before,
.evidence-library--human-review .human-review-card::before {
  position: absolute;
  top: 15px;
  bottom: 15px;
  left: 0;
  width: 3px;
  content: "";
  background: #80a9cf;
  border-radius: 0 999px 999px 0;
}

.evidence-card__preview {
  display: grid;
  width: 56px;
  height: 56px;
  grid-area: preview;
  place-items: center;
  overflow: hidden;
  background: transparent;
  border: 0;
}

.evidence-card--compact .evidence-card__preview .evidence-card__icon {
  display: grid;
  width: 46px;
  height: 48px;
  grid-area: auto;
  background: linear-gradient(145deg, var(--file-tint, #f4f7fb), #fff);
  border: 1px solid var(--file-border, #dbe5f1);
  border-radius: 15px;
  box-shadow: inset 0 1px 0 #ffffffd9, 0 8px 18px #5b749014;
}

.evidence-card--compact .evidence-card__main {
  align-self: center;
  gap: 4px;
}

.evidence-card--compact .evidence-card__main > small:not(.evidence-card__filename) {
  overflow: hidden;
  color: #8a96a8;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-card__labels {
  display: flex;
  min-width: 0;
  grid-area: labels;
  gap: 5px;
  overflow: hidden;
}

.evidence-card__labels em {
  min-width: 0;
  padding: 4px 6px;
  overflow: hidden;
  color: #5f7188;
  background: #edf3f8;
  border-radius: 999px;
  font-size: 9px;
  font-style: normal;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.evidence-card__labels em:last-child {
  color: #8b5d35;
  background: #fff0df;
}

.evidence-library--human-review .evidence-card-strip {
  align-items: stretch;
  flex-direction: column;
  padding-right: 6px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  scroll-snap-type: y proximity;
}

.evidence-library--human-review .evidence-card.evidence-card--compact {
  flex: 0 0 116px;
  width: 100%;
  min-width: 0;
  background: linear-gradient(145deg, #fffdfb, #fff7f0);
  border-color: #e9cdb9;
  box-shadow: 0 8px 20px #8f5c3912;
}

.evidence-library--human-review .human-review-card::before {
  width: 4px;
  background: #d59061;
}

.evidence-library--human-review .human-review-card .evidence-card__main > strong {
  color: #674632;
}

.evidence-library--human-review .human-review-card .evidence-card__main > small {
  color: #9a7259;
  font-weight: 700;
}

.evidence-library--human-review .human-review-card .evidence-card__labels em:last-child {
  color: #9b5b38;
  background: #ffe7d5;
}

.evidence-library--human-review .human-review-card .evidence-card__labels em:first-child {
  color: #8a634b;
  background: #f9ede4;
}

.evidence-card__preview:has(.evidence-file-icon[data-file-kind="image"]) {
  background: linear-gradient(145deg, #e5faf4, #fff5cf);
  border-radius: 14px;
  box-shadow: inset 0 0 0 1px #c9eee3;
}

.evidence-card__preview:has(.evidence-file-icon[data-file-kind="video"]) {
  background: linear-gradient(145deg, #fff0f7, #f2ebff);
  border-radius: 14px;
  box-shadow: inset 0 0 0 1px #f2d3e4;
}

.evidence-card__preview:has(.evidence-file-icon[data-file-kind="pdf"]) {
  background: linear-gradient(145deg, #fff0eb, #fff8e3);
  border-radius: 14px;
  box-shadow: inset 0 0 0 1px #f5d2c7;
}

.evidence-card__preview:has(.evidence-file-icon[data-file-kind="word"]) {
  background: linear-gradient(145deg, #eaf4ff, #eff4ff);
  border-radius: 14px;
  box-shadow: inset 0 0 0 1px #cfdfff;
}

.evidence-card__preview:has(.evidence-file-icon[data-file-kind="markdown"]),
.evidence-card__preview:has(.evidence-file-icon[data-file-kind="text"]),
.evidence-card__preview:has(.evidence-file-icon[data-file-kind="document"]) {
  background: linear-gradient(145deg, #f0edff, #f8f1ff);
  border-radius: 14px;
  box-shadow: inset 0 0 0 1px #ddd7f6;
}

.evidence-card__preview:has(.evidence-file-icon[data-file-kind="other"]) {
  background: linear-gradient(145deg, #eef4f8, #f2faf7);
  border-radius: 14px;
  box-shadow: inset 0 0 0 1px #d6e3e8;
}

.evidence-board__cards .evidence-empty {
  flex: 0 0 100%;
  margin: 4px 0 0;
}

.evidence-board__cards .evidence-library__actions {
  justify-content: flex-end;
  margin-top: 6px;
}

@media (max-width: 620px) {
  .evidence-board__cards {
    grid-template-columns: minmax(0, 1fr) 170px;
  }

  .evidence-card.evidence-card--compact {
    grid-template-columns: 52px minmax(0, 1fr);
    grid-template-areas:
      "preview main"
      "labels labels";
  }

  .evidence-card__preview {
    width: 52px;
    height: 52px;
  }
}
</style>
