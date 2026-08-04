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
import { disputeApi } from "../../api/disputes";
import {
  extractAgentRunDescriptor,
  loadActiveAgentRuns,
} from "../../api/agentStream";
import { roomApi } from "../../api/rooms";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import ExpandableText from "../../components/common/ExpandableText.vue";
import ConversationStream from "../../components/room/ConversationStream.vue";
import RoomShell from "../../components/room/RoomShell.vue";
import { actor } from "../../state/actor";
import {
  createRoomState,
  primeRoomEventCursor,
  streamRoomEvents,
} from "../../stores/room";
import {
  activeAgentStreams,
  abortAgentStream,
  clearAgentStreams,
  consumeAgentRun,
} from "../../stores/agentStream";
import { disputeStore } from "../../stores/dispute";
import {
  humanizeDossierList,
  humanizeDossierText,
  roleLabel,
} from "../../utils/displayText";
import { normalizeVerificationFocus } from "../../utils/verificationFocus";

const INTAKE_PROCESS_PROJECTION_SCHEMA = "intake-process-projection.v1";
const INTAKE_PROCESS_PROJECTION_STATES = new Set([
  "CURRENT",
  "PROCESSING",
  "UNAVAILABLE",
]);
const INTAKE_PROCESS_WRITERS = new Set(["SHADOW", "TEMPORAL"]);
const INTAKE_PROCESS_PHASES = new Set([
  "OPEN",
  "WAITING_PARTY",
  "AGENT_RUNNING",
  "READY_TO_CONFIRM",
  "CLOSED",
  "COMPLETED",
]);
const INTAKE_COMMAND_ADMISSION_STATES = new Set(["PENDING", "READY"]);
const INTAKE_PENDING_STATE_BY_PHASE = {
  OPEN: "NONE",
  WAITING_PARTY: "WAITING_PARTY",
  AGENT_RUNNING: "AGENT_RUNNING",
  READY_TO_CONFIRM: "NONE",
  CLOSED: "NONE",
  COMPLETED: "NONE",
};
const PROJECTION_FIELD_CONFLICT = Symbol("projection-field-conflict");
const PROJECTION_MISSING = Symbol("projection-missing");
const READINESS_RETRY_FAST_ATTEMPTS = 40;
const READINESS_RETRY_FAST_DELAY_MS = 250;
const READINESS_RETRY_SLOW_DELAY_MS = 1_000;
const FORMAL_READINESS_EVENT_TYPE = "INTAKE_PROJECTION_READY";
const FORMAL_AGENT_SENDER_ROLES = new Set([
  "CUSTOMER_SERVICE",
  "INTAKE_OFFICER",
  "DISPUTE_INTAKE_OFFICER",
]);

function projectionValuesEqual(left, right) {
  if (Object.is(left, right)) return true;
  if (Array.isArray(left) || Array.isArray(right)) {
    return (
      Array.isArray(left) &&
      Array.isArray(right) &&
      left.length === right.length &&
      left.every((value, index) => projectionValuesEqual(value, right[index]))
    );
  }
  if (
    !left ||
    !right ||
    typeof left !== "object" ||
    typeof right !== "object"
  ) {
    return false;
  }
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  return (
    leftKeys.length === rightKeys.length &&
    leftKeys.every((key) =>
      Object.hasOwn(right, key) && projectionValuesEqual(left[key], right[key]),
    )
  );
}

function projectionField(projection, snakeCase, camelCase) {
  const hasSnakeCase = Object.hasOwn(projection, snakeCase);
  const hasCamelCase = Object.hasOwn(projection, camelCase);
  if (
    hasSnakeCase &&
    hasCamelCase &&
    !projectionValuesEqual(projection[snakeCase], projection[camelCase])
  ) {
    return PROJECTION_FIELD_CONFLICT;
  }
  if (hasSnakeCase) return projection[snakeCase];
  if (hasCamelCase) return projection[camelCase];
  return undefined;
}

function declaredProcessProjection(status) {
  if (!status || typeof status !== "object") return PROJECTION_MISSING;
  const hasSnakeCase = Object.hasOwn(status, "process_projection");
  const hasCamelCase = Object.hasOwn(status, "processProjection");
  if (!hasSnakeCase && !hasCamelCase) return PROJECTION_MISSING;
  if (
    hasSnakeCase &&
    hasCamelCase &&
    !projectionValuesEqual(status.process_projection, status.processProjection)
  ) {
    return PROJECTION_FIELD_CONFLICT;
  }
  return hasSnakeCase ? status.process_projection : status.processProjection;
}

function projectionEnum(value) {
  return typeof value === "string" ? value.trim().toUpperCase() : "";
}

function processingProjection() {
  return {
    mode: "PROCESSING",
    writerMode: "",
    commandAdmissionState: "",
    roomPhase: "",
    pendingState: "",
    activeLogicalRunId: "",
  };
}

function legacyProjection() {
  return {
    mode: "LEGACY",
    writerMode: "LEGACY",
    commandAdmissionState: "",
    roomPhase: "",
    pendingState: "",
    activeLogicalRunId: "",
  };
}

function normalizeIntakeProcessProjection(status) {
  const projection = declaredProcessProjection(status);
  if (projection === PROJECTION_MISSING) return legacyProjection();
  if (
    projection === PROJECTION_FIELD_CONFLICT ||
    !projection ||
    typeof projection !== "object" ||
    Array.isArray(projection)
  ) {
    return processingProjection();
  }

  const fields = {
    schemaVersion: projectionField(projection, "schema_version", "schemaVersion"),
    projectionState: projectionField(
      projection,
      "projection_state",
      "projectionState",
    ),
    writerMode: projectionField(projection, "writer_mode", "writerMode"),
    commandAdmissionState: projectionField(
      projection,
      "command_admission_state",
      "commandAdmissionState",
    ),
    roomEpoch: projectionField(projection, "room_epoch", "roomEpoch"),
    processRevision: projectionField(
      projection,
      "process_revision",
      "processRevision",
    ),
    roomRevision: projectionField(projection, "room_revision", "roomRevision"),
    fencingToken: projectionField(projection, "fencing_token", "fencingToken"),
    roomPhase: projectionField(projection, "room_phase", "roomPhase"),
    pendingState: projectionField(projection, "pending_state", "pendingState"),
    activeLogicalRunId: projectionField(
      projection,
      "active_logical_run_id",
      "activeLogicalRunId",
    ),
    activeAttemptId: projectionField(
      projection,
      "active_attempt_id",
      "activeAttemptId",
    ),
    activeRunStatus: projectionField(
      projection,
      "active_run_status",
      "activeRunStatus",
    ),
    streamCursor: projectionField(projection, "stream_cursor", "streamCursor"),
    versionPins: projectionField(projection, "version_pins", "versionPins"),
    projectedAt: projectionField(projection, "projected_at", "projectedAt"),
  };
  if (Object.values(fields).includes(PROJECTION_FIELD_CONFLICT)) {
    return processingProjection();
  }
  if (fields.schemaVersion !== INTAKE_PROCESS_PROJECTION_SCHEMA) {
    return processingProjection();
  }

  const projectionState = projectionEnum(fields.projectionState);
  if (!INTAKE_PROCESS_PROJECTION_STATES.has(projectionState)) {
    return processingProjection();
  }

  const writerMode = projectionEnum(fields.writerMode);
  if (projectionState === "UNAVAILABLE") {
    return writerMode === "LEGACY" ? legacyProjection() : processingProjection();
  }
  if (projectionState === "PROCESSING") return processingProjection();
  if (writerMode === "LEGACY") return legacyProjection();

  const commandAdmissionState = projectionEnum(fields.commandAdmissionState);
  if (
    fields.commandAdmissionState !== undefined &&
    !INTAKE_COMMAND_ADMISSION_STATES.has(commandAdmissionState)
  ) {
    return processingProjection();
  }

  const roomPhase = projectionEnum(fields.roomPhase);
  const pendingState = projectionEnum(fields.pendingState);
  if (
    !INTAKE_PROCESS_WRITERS.has(writerMode) ||
    !INTAKE_PROCESS_PHASES.has(roomPhase) ||
    pendingState !== INTAKE_PENDING_STATE_BY_PHASE[roomPhase]
  ) {
    return processingProjection();
  }

  const revisionFields = [
    fields.roomEpoch,
    fields.processRevision,
    fields.roomRevision,
  ];
  if (
    revisionFields.some((value) => !Number.isSafeInteger(value) || value < 0) ||
    !Number.isSafeInteger(fields.fencingToken) ||
    fields.fencingToken < 1
  ) {
    return processingProjection();
  }

  const activeLogicalRunId = fields.activeLogicalRunId;
  const activeAttemptId = fields.activeAttemptId;
  const activeRunStatus = fields.activeRunStatus;
  const streamCursor = fields.streamCursor;

  if (activeLogicalRunId == null) {
    if (
      activeAttemptId != null ||
      activeRunStatus != null ||
      streamCursor != null
    ) {
      return processingProjection();
    }
    return {
      mode: "CURRENT",
      writerMode,
      commandAdmissionState,
      roomPhase,
      pendingState,
      activeLogicalRunId: "",
    };
  }

  if (
    typeof activeLogicalRunId !== "string" ||
    !activeLogicalRunId.trim() ||
    typeof activeRunStatus !== "string" ||
    !["PENDING", "RUNNING"].includes(activeRunStatus.trim().toUpperCase()) ||
    typeof streamCursor !== "string"
  ) {
    return processingProjection();
  }

  const normalizedRunId = activeLogicalRunId.trim();
  if (streamCursor === "-1") {
    if (
      activeAttemptId != null &&
      (typeof activeAttemptId !== "string" || !activeAttemptId.trim())
    ) {
      return processingProjection();
    }
    return {
      mode: "CURRENT",
      writerMode,
      commandAdmissionState,
      roomPhase,
      pendingState,
      activeLogicalRunId: normalizedRunId,
    };
  }

  if (typeof activeAttemptId !== "string" || !activeAttemptId.trim()) {
    return processingProjection();
  }
  const cursorMatch = /^v2:(.+):(0|[1-9]\d*)$/.exec(streamCursor);
  if (!cursorMatch || cursorMatch[1] !== activeAttemptId.trim()) {
    return processingProjection();
  }
  return {
    mode: "CURRENT",
    writerMode,
    commandAdmissionState,
    roomPhase,
    pendingState,
    activeLogicalRunId: normalizedRunId,
  };
}

const props = defineProps({
  initialDispute: { type: Object, default: null },
  initialAnalysis: { type: Object, default: null },
  initialTurnMemory: { type: Object, default: null },
  initialIntakeStatus: { type: Object, default: null },
  initialMessages: { type: Array, default: null },
  messagesLoader: { type: Function, default: null },
  turnMemoryLoader: { type: Function, default: null },
  intakeStatusLoader: { type: Function, default: null },
  postMessageAction: { type: Function, default: null },
  openingAction: { type: Function, default: null },
  confirmAction: { type: Function, default: null },
  cancelAction: { type: Function, default: null },
  eventStreamer: { type: Function, default: null },
  modelHealthLoader: { type: Function, default: null },
  evidenceReadyPollAttempts: { type: Number, default: 4 },
  evidenceReadyPollDelayMs: { type: Number, default: 200 },
  formalReadinessPollAttempts: { type: Number, default: 13 },
  formalReadinessPollDelayMs: { type: Number, default: 5_000 },
});

const route = useRoute();
const router = useRouter();
const dispute = ref(props.initialDispute);
const analysis = ref(props.initialAnalysis);
const turnMemory = ref(props.initialTurnMemory);
const intakeStatus = ref(props.initialIntakeStatus);
const streamedCaseDetailSections = ref({});
const pendingOriginalStatement = ref("");
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
const modelConnectionState = ref("checking");
const workspaceGeneration = ref(0);
let eventAbortController = new AbortController();
let modelHealthTimer = null;
let modelHealthInFlight = null;
let projectionStatusRetryTimer = null;
let projectionStatusRetryInFlight = null;
let projectionStatusRetryAttempts = 0;
let openingRunRetryTimer = null;
let openingRunRetryInFlight = null;
let openingRunRetryAttempts = 0;
let formalReadinessRetryTimer = null;
let formalReadinessRetryCancel = null;
let formalReadinessRetryWake = null;
let formalReadinessRetryToken = 0;
let formalReadinessActiveRunId = "";
let formalReadinessReadySignalSequence = 0;
let roomMessagesRefreshToken = 0;
let roomTurnMemoryRefreshToken = 0;
let roomIntakeStatusRefreshToken = 0;
let roomSnapshotWriteBarrier = 0;
let awaitingTemporalInitiatorRun = false;
let componentUnmounted = false;

const caseId = computed(() => dispute.value?.id || route.params.caseId);
const historyMode = computed(() => route.query.view === "history");
const intakeProcessProjection = computed(() =>
  normalizeIntakeProcessProjection(intakeStatus.value),
);
const projectionAllowsMessages = computed(() => {
  const projection = intakeProcessProjection.value;
  return projection.mode === "LEGACY" ||
  (
    projection.mode === "CURRENT" &&
    ["OPEN", "WAITING_PARTY", "READY_TO_CONFIRM"].includes(
      projection.roomPhase,
    ) &&
    (
      projection.writerMode !== "TEMPORAL" ||
      targetTemporalCommandAdmissionReady(projection)
    )
  );
});
const projectionAllowsConfirmation = computed(() => {
  const projection = intakeProcessProjection.value;
  return projection.mode === "LEGACY" ||
  (
    projection.mode === "CURRENT" &&
    projection.roomPhase === "READY_TO_CONFIRM" &&
    targetTemporalCommandAdmissionAllowed(projection)
  );
});
const projectionAllowsCancellation = computed(() => {
  const projection = intakeProcessProjection.value;
  return projection.mode === "LEGACY" ||
  (
    projection.mode === "CURRENT" &&
    ["OPEN", "WAITING_PARTY", "READY_TO_CONFIRM"].includes(
      projection.roomPhase,
    ) &&
    targetTemporalCommandAdmissionAllowed(projection)
  );
});
const projectionAllowsEvidence = computed(() =>
  intakeProcessProjection.value.mode === "LEGACY" ||
  (
    intakeProcessProjection.value.mode === "CURRENT" &&
    intakeProcessProjection.value.roomPhase === "COMPLETED"
  ),
);
const actorPartyPosition = computed(() => {
  const explicit = String(
    dispute.value?.party_position || dispute.value?.partyPosition || "",
  ).toUpperCase();
  if (["INITIATOR", "RESPONDENT", "OBSERVER"].includes(explicit)) {
    return explicit;
  }

  const actorId = String(actor.id || "");
  const actorRole = normalizePartyRoleValue(actor.role);
  const initiatorId = String(
    dispute.value?.initiator_id || dispute.value?.initiatorId || "",
  );
  const respondentId = String(
    dispute.value?.respondent_id || dispute.value?.respondentId || "",
  );
  const firstPartyTurn = (messages.value || []).find((message) =>
    ["USER", "MERCHANT"].includes(
      normalizePartyRoleValue(message.sender_role || message.senderRole),
    ),
  );
  const initiatorRole = normalizePartyRoleValue(
    intakeStatus.value?.initiator_role ||
      intakeStatus.value?.initiatorRole ||
      dispute.value?.initiator_role ||
      dispute.value?.initiatorRole ||
      analysis.value?.initiator_role ||
      analysis.value?.initiatorRole ||
      firstPartyTurn?.sender_role ||
      firstPartyTurn?.senderRole,
  );
  const respondentRole = normalizePartyRoleValue(
    intakeStatus.value?.respondent_role ||
      intakeStatus.value?.respondentRole ||
      dispute.value?.respondent_role ||
      dispute.value?.respondentRole ||
      oppositePartyRole(initiatorRole),
  );
  if (actorId && actorId === initiatorId && actorRole === initiatorRole) {
    return "INITIATOR";
  }
  if (actorId && actorId === respondentId && actorRole === respondentRole) {
    return "RESPONDENT";
  }
  if (!initiatorId && !respondentId) {
    if (actorRole === initiatorRole) return "INITIATOR";
    if (actorRole === oppositePartyRole(initiatorRole)) return "RESPONDENT";
  }
  return "OBSERVER";
});
const shouldDiscoverActiveIntakeRuns = computed(() =>
  !historyMode.value &&
  props.initialMessages === null &&
  props.initialDispute === null &&
  !props.messagesLoader,
);
const intakeStreamingRuns = computed(() =>
  activeAgentStreams({
    caseId: caseId.value,
    roomType: "INTAKE",
    actorId: actor.id,
    actorRole: actor.role,
  }),
);
const intakeCancellationDisabled = computed(() =>
  historyMode.value ||
  !projectionAllowsCancellation.value ||
  !serverPartyCanChat.value ||
  submitting.value ||
  admitted.value ||
  intakeStreamingRuns.value.length > 0,
);
const caseNoteTitle = computed(() =>
  humanizeDossierText(dispute.value?.title || "履约争端", {
    kind: "title",
    fallback: "履约争端",
  }),
);
const caseNoteDescription = computed(() =>
  humanizeDossierText(dispute.value?.description || "", {
    kind: "summary",
    fallback: "接待官正在整理争议事实，请继续补充案件经过、当前状态和处理诉求。",
  }),
);
const serverPartyCanChat = computed(() => {
  const serverValue = intakeStatus.value?.can_use_intake ?? intakeStatus.value?.canUseIntake;
  if (typeof serverValue === "boolean") return serverValue;
  return actorPartyPosition.value === "INITIATOR";
});
const partyCanChat = computed(() =>
  serverPartyCanChat.value && projectionAllowsMessages.value,
);
const ownIntakeFormalizationPending = computed(() =>
  serverPartyCanChat.value &&
  !projectionAllowsMessages.value &&
  (submitting.value || intakeStreamingRuns.value.length > 0),
);
const intakeComposerVisible = computed(() =>
  !historyMode.value &&
  (partyCanChat.value || ownIntakeFormalizationPending.value),
);
const intakeComposerHint = computed(() =>
  ownIntakeFormalizationPending.value
    ? "本轮回复已生成，正在同步正式卷宗；完成后即可继续补充。"
    : "消息提交后成为不可变房间记录",
);
const currentActorIntakeCompleted = computed(() => Boolean(
  intakeStatus.value?.current_actor_completed ??
  intakeStatus.value?.currentActorCompleted,
));
const canEnterEvidence = computed(() => Boolean(
  projectionAllowsEvidence.value &&
  (intakeStatus.value?.can_enter_evidence ?? intakeStatus.value?.canEnterEvidence),
));
const modelConnected = computed(() => modelConnectionState.value === "connected");
const modelConnectionLabel = computed(() => {
  if (historyMode.value) return "历史记录已封存";
  if (intakeStreamingRuns.value.length) return "数字人正在输出";
  if (modelConnectionState.value === "connected") return "数字人已连接";
  if (modelConnectionState.value === "checking") return "连接检测中";
  return "数字人未连接";
});
const intakeConversationEmptyText = computed(() => {
  if (historyMode.value) return "该接待室没有可供浏览的历史对话。";
  if (!modelConnected.value) {
    return "数字人未连接，恢复连接后将生成首轮案情追问。";
  }
  if (!initialAgentReady.value) {
    return "接待官正在依据案件表单生成首轮案情追问，请稍候。";
  }
  if (!currentActorIsInitiator.value && partyCanChat.value) {
    return "请说明你对右侧案情、发起方诉求和争议事实的回应。";
  }
  return "接待官的首轮追问正在同步到对话记录。";
});
const intakeComposerDisabledReason = computed(() => {
  if (historyMode.value) {
    return "历史接待记录仅供浏览，不能再次提交陈述。";
  }
  if (!["USER", "MERCHANT"].includes(actor.role)) {
    return "当前是平台观察/审核身份。请切换为用户或商家身份，才能继续与争议接待官对话。";
  }
  if (!serverPartyCanChat.value) {
    if (intakeRecipientView.value) {
      return "发起方完成接待后，你的私有接待会话会自动开放。";
    }
    if (currentActorIntakeCompleted.value) {
      return "你已完成本方接待，正在等待对方完成接待。";
    }
    return "当前接待会话暂不可用，请等待接待状态同步。";
  }
  if (!projectionAllowsMessages.value) {
    return "本轮回复已生成，正在同步正式卷宗；完成后即可继续补充。";
  }
  if (!modelConnected.value) {
    return modelConnectionState.value === "checking"
      ? "正在检测数字人模型连接，连接成功后才能发布陈述。"
      : "数字人未连接，模型服务恢复后才能继续提交陈述。";
  }
  return "";
});
const intakeWorkStatus = computed(() => {
  if (historyMode.value) return "HISTORY";
  if (
    intakeStreamingRuns.value.length ||
    String(agentState.value).toUpperCase() === "STREAMING"
  ) return "STREAMING_RESPONSE";
  if (!modelConnected.value) {
    return modelConnectionState.value === "checking"
      ? "MODEL_CONNECTING"
      : "MODEL_DISCONNECTED";
  }
  if (error.value) return "ERROR";
  if (admitted.value) return "HANDOFF";
  if (!initialAgentReady.value) return "GENERATING_INITIAL";
  if (submitting.value || agentState.value === "THINKING") return "GENERATING_RESPONSE";
  return "READY_FOR_SUPPLEMENT";
});
const intakeWorkStatusCopy = computed(() => {
  const copies = {
    HISTORY: {
      eyebrow: "ARCHIVED INTAKE",
      title: "历史接待记录已封存",
      description: "可以浏览当时的对话与案情卷宗，陈述、受理和流程推进均已锁定。",
      tone: "ready",
    },
    MODEL_CONNECTING: {
      eyebrow: "MODEL STATUS",
      title: "正在检测数字人连接",
      description: "正在确认接待官模型是否可用，连接成功后开放陈述输入。",
      tone: "working",
    },
    MODEL_DISCONNECTED: {
      eyebrow: "MODEL OFFLINE",
      title: "数字人未连接",
      description: "模型服务暂不可用，恢复连接后才能继续提交陈述。",
      tone: "error",
    },
    GENERATING_INITIAL: {
      eyebrow: "INTAKE STATUS",
      title: "接待官正在整理案情",
      description: "正在读取表单、订单引用与初始诉求，生成首轮追问和右侧展板。",
      tone: "working",
    },
    GENERATING_RESPONSE: {
      eyebrow: "INTAKE STATUS",
      title: "接待官正在生成回复",
      description: "正在吸收本轮陈述，并同步更新案情卷宗。",
      tone: "working",
    },
    STREAMING_RESPONSE: {
      eyebrow: "LIVE GENERATION",
      title: "接待官正在流式输出",
      description: "回复与案情展板正在同步生成，请等待输出完成后再继续补充。",
      tone: "streaming",
    },
    READY_FOR_SUPPLEMENT: {
      eyebrow: "READY",
      title: "接待官已就绪",
      description: "请根据追问补充案情事实；证据材料后续在证据室提交。",
      tone: "ready",
    },
    HANDOFF: {
      eyebrow: "HANDOFF",
      title: "接待室已封存",
      description: "案情卷宗已上报，下一步进入证据室。",
      tone: "handoff",
    },
    ERROR: {
      eyebrow: "INTAKE ERROR",
      title: "接待官生成失败",
      description: "模型服务暂不可用，请稍后重试；当前不会写入兜底卷宗。",
      tone: "error",
    },
  };
  return copies[intakeWorkStatus.value] || copies.READY_FOR_SUPPLEMENT;
});
const connectionState = computed(() => {
  // A live AgentRun is stronger connectivity evidence than a stale/failed
  // durable-room stream probe. Keep the room header consistent with the
  // visible LIVE GENERATION state while tokens are arriving.
  if (intakeStreamingRuns.value.length > 0) return "connected";
  if (eventState.connected) return "connected";
  if (eventState.reconnecting) return "reconnecting";
  return "offline";
});
const scrollSnapshot = computed(() =>
  turnMemory.value?.scroll_snapshot || turnMemory.value?.scrollSnapshot || null,
);
const currentCaseDossier = computed(() =>
  turnMemory.value?.case_intake_dossier || turnMemory.value?.caseIntakeDossier || null,
);
const supportedCaseDetailSchemas = new Set([
  "intake_case_detail.v1",
  "intake-dossier.v2",
]);
function isSupportedCaseDetailDossier(value) {
  const schemaVersion = value?.schema_version || value?.schemaVersion;
  return Boolean(value && supportedCaseDetailSchemas.has(schemaVersion));
}

function isCaseDetailObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function deepMergeCaseDetail(base, patch) {
  const merged = { ...(isCaseDetailObject(base) ? base : {}) };
  Object.entries(isCaseDetailObject(patch) ? patch : {}).forEach(([key, incoming]) => {
    const existing = merged[key];
    merged[key] = isCaseDetailObject(existing) && isCaseDetailObject(incoming)
      ? deepMergeCaseDetail(existing, incoming)
      : incoming;
  });
  return merged;
}

function mergeStreamedCaseDetail(base, sections) {
  const streamedEntries = Object.entries(sections || {});
  if (!streamedEntries.length) return base;
  return deepMergeCaseDetail({
    ...(isCaseDetailObject(base) ? base : {}),
    schema_version: base?.schema_version || "intake_case_detail.v1",
  }, sections);
}
const caseDetailDossier = computed(() => {
  const current = currentCaseDossier.value?.dossier;
  const persisted = isSupportedCaseDetailDossier(current)
    ? current
    : isSupportedCaseDetailDossier(scrollSnapshot.value)
      ? scrollSnapshot.value
      : null;
  return mergeStreamedCaseDetail(persisted, streamedCaseDetailSections.value);
});
const isCaseDetailDossier = computed(() => Boolean(caseDetailDossier.value));
const initialAgentReady = computed(() => Boolean(caseDetailDossier.value));
const currentMatrixKind = computed(() => {
  const detail = caseDetailDossier.value;
  const caseFactMatrix = detail?.case_fact_matrix || detail?.caseFactMatrix;
  const schemaVersion = caseFactMatrix?.schema_version || caseFactMatrix?.schemaVersion;
  if (schemaVersion !== "case_fact_matrix.v2") return "";
  return String(
    caseFactMatrix.matrix_kind || caseFactMatrix.matrixKind || "",
  ).toUpperCase();
});
const currentActorMatrixReady = computed(() =>
  currentActorIsInitiator.value || currentMatrixKind.value === "BILATERAL_FROZEN",
);
const intakeDossierSubmissionDisabled = computed(() =>
  historyMode.value ||
  !projectionAllowsConfirmation.value ||
  !serverPartyCanChat.value ||
  submitting.value ||
  admitted.value ||
  intakeStreamingRuns.value.length > 0 ||
  !initialAgentReady.value ||
  !modelConnected.value ||
  !currentActorMatrixReady.value,
);
const caseDetailQuality = computed(() => {
  const quality = caseDetailDossier.value?.intake_quality || {};
  const respondentStartsIndependently =
    !currentActorIsInitiator.value &&
    partyCanChat.value &&
    currentMatrixKind.value !== "BILATERAL_FROZEN";
  if (respondentStartsIndependently) {
    return {
      score: 0,
      threshold: quality.threshold ?? 85,
      ready: false,
      reason: "被发起方完善度从本方陈述开始独立统计",
    };
  }
  return {
    score: currentCaseDossier.value?.quality_score ?? quality.score ?? 0,
    threshold: quality.threshold ?? 85,
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
    summary: humanizeDossierText(detail?.case_story?.one_sentence_summary || "", {
      kind: "summary",
      fallback: "",
    }),
    coreIssue: humanizeDossierText(detail?.dispute_focus?.core_issue || "UNKNOWN"),
  };
});
// 业务位置：【前端接待室】displayReferenceValue：将 案件和订单引用标识 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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
// 业务位置：【前端接待室】compactText：将 面向当事人的业务文本 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function compactText(...values) {
  return values
    .flat()
    .map((value) => String(value || "").trim())
    .filter(Boolean)
    .join(" ");
}
// 业务位置：【前端接待室】legacyDossierSignalText：围绕 案件卷宗 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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
// 业务位置：【前端接待室】inferResolutionCode：根据已有 当前阶段业务数据 推导本阶段的业务判断，供后续 Agent 或人工审核使用。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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
// 业务位置：【前端接待室】meaningfulResponseText：围绕 面向当事人的业务文本 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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
// 业务位置：【前端接待室】resolveRespondentRole：读取 当事人主张、角色和对方态度，并依据当前案件、角色和会话权限裁剪成可用输入。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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
// 业务位置：【前端接待室】partyPositionForRole：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function partyPositionForRole(detail, role) {
  if (role === "USER") {
    return detail?.party_positions?.user_claim || analysis.value?.party_claims?.user;
  }
  if (role === "MERCHANT") {
    return detail?.party_positions?.merchant_claim || analysis.value?.party_claims?.merchant;
  }
  return "";
}
// 业务位置：【前端接待室】inferRespondentAttitude：根据已有 当事人主张、角色和对方态度 推导本阶段的业务判断，供后续 Agent 或人工审核使用。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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
// 业务位置：【前端接待室】isSignedNotReceivedContext：判断 案件会话和上下文快照 是否满足当前流程分支的进入条件。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function isSignedNotReceivedContext(detail) {
  return /物流|签收|未收到|没收到|包裹|快递/u.test(legacyDossierSignalText(detail));
}
// 业务位置：【前端接待室】hasReferenceValue：判断 案件和订单引用标识 是否满足当前流程分支的进入条件。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function hasReferenceValue(value) {
  const text = String(value || "").trim();
  return Boolean(text && !/^(待补充|待确认|UNKNOWN|PENDING)$/i.test(text));
}
// 业务位置：【前端接待室】fallbackFactsInDispute：在模型或外部服务不可用时，为 当前阶段业务数据 生成保守降级结果，使案件转入可继续追问或人工处理的路径。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function fallbackFactsInDispute(detail) {
  if (!isSignedNotReceivedContext(detail)) return [];
  return ["用户是否实际收到商品", "签收记录是否足以证明本人收货"];
}
// 业务位置：【前端接待室】fallbackVerificationGaps：在模型或外部服务不可用时，为 当前阶段业务数据 生成保守降级结果，使案件转入可继续追问或人工处理的路径。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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
// 业务位置：【前端接待室】hasKnownPartyLabel：判断 当事人主张、角色和对方态度 是否满足当前流程分支的进入条件。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function hasKnownPartyLabel(label) {
  return Boolean(label && !["待确认", "未知身份"].includes(label));
}
// 业务位置：【前端接待室】claimActionTextFor：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function claimActionTextFor(initiator, resolution) {
  if (resolution === "待确认诉求") return hasKnownPartyLabel(initiator) ? `${initiator}诉求待确认` : "诉求待确认";
  return hasKnownPartyLabel(initiator) ? `${initiator}请求${resolution}` : `请求${resolution}`;
}
// 业务位置：【前端接待室】attitudeActionTextFor：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function attitudeActionTextFor(respondent, attitudeLabel) {
  return hasKnownPartyLabel(respondent) ? `${respondent}${attitudeLabel}` : `对方${attitudeLabel}`;
}
// 业务位置：【前端接待室】partySubject：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function partySubject(label, fallback) {
  return hasKnownPartyLabel(label) ? label : fallback;
}
// 业务位置：【前端接待室】respondentNoResponseText：围绕 面向当事人的业务文本 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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
  const viewerIsInitiator = actorPartyPosition.value === "INITIATOR";
  const viewerIsRespondent = actorPartyPosition.value === "RESPONDENT";
  const claimLabel = viewerIsInitiator
    ? `我方（${initiator}）诉求`
    : viewerIsRespondent
      ? `对方（${initiator}）诉求`
      : `发起方（${initiator}）诉求`;
  const responseLabel = viewerIsInitiator
    ? `对方（${respondent}）回应`
    : viewerIsRespondent
      ? `我方（${respondent}）回应`
      : `被发起方（${respondent}）回应`;
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
  const responseHasSubjectiveSource = /主观|单方陈述|发起方陈述/u.test(
    String(attitude.source || ""),
  );
  const mayShowResponse =
    !viewerIsInitiator && !(viewerIsRespondent && responseHasSubjectiveSource);
  const attitudeSummary =
    mayShowResponse && (inferredAttitude.hasResponse || inferredAttitude.showSummary)
      ? inferredAttitude.summary
      : "暂无回应";
  return {
    initiator,
    respondent,
    claimLabel,
    responseLabel,
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
  const initiator = roleLabel(initiatorRoleValue.value || "UNKNOWN");
  const initiatorRole = normalizePartyRoleValue(initiatorRoleValue.value);
  const respondentRole = oppositePartyRole(initiatorRole);
  const respondent = roleLabel(respondentRole);
  const viewerIsInitiator = actorPartyPosition.value === "INITIATOR";
  const viewerIsRespondent = actorPartyPosition.value === "RESPONDENT";
  const resolution = "待接待官整理";
  return {
    initiator,
    respondent,
    claimLabel: viewerIsInitiator
      ? `我方（${initiator}）诉求`
      : viewerIsRespondent
        ? `对方（${initiator}）诉求`
        : `发起方（${initiator}）诉求`,
    responseLabel: viewerIsInitiator
      ? `对方（${respondent}）回应`
      : viewerIsRespondent
        ? `我方（${respondent}）回应`
        : `被发起方（${respondent}）回应`,
    resolution,
    resolutionActionText: claimActionTextFor(initiator, resolution),
    requestedItems: "",
    amountDisplay: "",
    attitudeLabel: respondentAttitudeLabels.NOT_RESPONDED,
    attitudeActionText: attitudeActionTextFor(respondent, respondentAttitudeLabels.NOT_RESPONDED),
    claimSummary: "等待接待官整理",
    claimMeta: "等待接待官整理",
    attitudeSummary: "暂无回应",
    showAttitudeSummary: false,
    attitudeMeta: `${respondent}：${respondentAttitudeLabels.NOT_RESPONDED}`,
    coreConflict: "",
    factsInDispute: [],
    nextFocus: [],
  };
});
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
    ...(claimStatus.value?.nextFocus || []),
    ...fallbackVerificationGaps(
      detail,
      respondentState.hasResponse,
      respondentRole,
    ),
  ];
  return normalizeVerificationFocus(humanizeDossierList(candidates, ""));
});
const verificationGaps = computed(() => allVerificationGaps.value.slice(0, 4));
const hiddenVerificationGapCount = computed(() =>
  Math.max(0, allVerificationGaps.value.length - verificationGaps.value.length),
);
const scrollCards = computed(() => scrollSnapshot.value?.cards || []);
// 业务位置：【前端接待室】scrollCardValue：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function scrollCardValue(key, fallback = "") {
  return scrollCards.value.find((card) => card.key === key)?.value || fallback;
}
const initiatorRoleValue = computed(() => {
  const explicitRole = normalizePartyRoleValue(
    intakeStatus.value?.initiator_role ||
    intakeStatus.value?.initiatorRole ||
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
    actorPartyPosition.value === "RESPONDENT" &&
    !serverPartyCanChat.value &&
    !currentActorIntakeCompleted.value,
);
const canManageIntake = computed(() => {
  const projection = intakeProcessProjection.value;
  return (
    ["INITIATOR", "RESPONDENT"].includes(actorPartyPosition.value) &&
    !intakeRecipientView.value &&
    serverPartyCanChat.value &&
    targetTemporalCommandAdmissionAllowed(projection) &&
    (projectionAllowsMessages.value || projectionAllowsConfirmation.value)
  );
});
const currentActorIsInitiator = computed(
  () => actorPartyPosition.value === "INITIATOR",
);
const confirmButtonCopy = computed(() =>
  currentActorIsInitiator.value ? "确认发起并上报" : "确认陈述并进入证据室",
);

// 业务位置：【前端接待室】currentWorkspaceSnapshot：围绕 页面工作区和业务快照 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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

// 业务位置：【前端接待室】isCurrentWorkspace：判断 页面工作区和业务快照 是否满足当前流程分支的进入条件。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function isCurrentWorkspace(snapshot) {
  return (
    !componentUnmounted &&
    snapshot &&
    snapshot.generation === workspaceGeneration.value &&
    snapshot.caseId === caseId.value &&
    snapshot.actor?.id === actor.id &&
    snapshot.actor?.role === actor.role
  );
}

// 业务位置：【前端接待室】resetWorkspaceForActorChange：更新 页面工作区和业务快照 的消息、缓存或持久记录，避免旧回合数据影响当前处理。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function resetWorkspaceForActorChange() {
  clearProjectionStatusRetry();
  clearOpeningRunRetry();
  clearFormalReadinessRetry();
  awaitingTemporalInitiatorRun = false;
  clearAgentStreams({ caseId: caseId.value, roomType: "INTAKE" });
  workspaceGeneration.value += 1;
  messages.value = [];
  turnMemory.value = null;
  intakeStatus.value = null;
  streamedCaseDetailSections.value = {};
  pendingOriginalStatement.value = "";
  admitted.value = false;
  resolved.value = false;
  error.value = "";
  agentState.value = "LISTENING";
  submitting.value = false;
  eventAbortController.abort();
  eventAbortController = new AbortController();
  eventState.connected = false;
  eventState.reconnecting = false;
  eventState.streamError = null;
}

// 业务位置：【前端接待室】normalizePartyRoleValue：将 当事人主张、角色和对方态度 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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

// 业务位置：【前端接待室】oppositePartyRole：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function oppositePartyRole(role) {
  const normalizedRole = normalizePartyRoleValue(role);
  if (normalizedRole === "USER") return "MERCHANT";
  if (normalizedRole === "MERCHANT") return "USER";
  return "UNKNOWN";
}

// 业务位置：【前端接待室】normalizedPartyRole：将 当事人主张、角色和对方态度 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function normalizedPartyRole(role) {
  return normalizePartyRoleValue(role);
}

const subjectiveStatement = computed(() => {
  const detail = caseDetailDossier.value;
  const claim = detail?.claim_resolution || {};
  const sourceRole = normalizedPartyRole(actor.role);
  const sourceRoleName = roleLabel(sourceRole);
  const initiatorStatement = currentActorIsInitiator.value
    ? claim.original_statement ||
      claim.originalStatement ||
      dispute.value?.description
    : "";
  const actorStatements = (messages.value || [])
    .filter(
      (message) =>
        normalizedPartyRole(message.sender_role || message.senderRole) === sourceRole,
    )
    .map((message) => String(message.message_text || message.messageText || ""))
    .filter((statement) => statement.trim());
  const persistedStatement =
    typeof initiatorStatement === "string" ? initiatorStatement : "";
  const pendingStatement = pendingOriginalStatement.value.trim();
  let visibleStatement = persistedStatement;
  for (const statement of actorStatements) {
    if (!visibleStatement.includes(statement)) {
      visibleStatement = [visibleStatement, statement].filter(Boolean).join("\n");
    }
  }
  if (pendingStatement && !visibleStatement.includes(pendingStatement)) {
    visibleStatement = [visibleStatement, pendingStatement].filter(Boolean).join("\n");
  }
  return {
    titleSuffix: `${sourceRoleName}原话`,
    label: "原始陈述",
    value: visibleStatement.trim() ? visibleStatement : "暂无原始陈述",
  };
});

// 业务位置：【前端接待室】openDossierFulltext：切换与 案件卷宗 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function openDossierFulltext(payload) {
  dossierFulltextReturnFocus = document.activeElement;
  dossierFulltext.value = payload;
  await nextTick();
  const dialog = dossierFulltextDialog.value;
  if (typeof dialog?.showModal === "function" && !dialog.open) {
    dialog.showModal();
  }
  dialog?.focus();
}

// 业务位置：【前端接待室】openVerificationGaps：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function openVerificationGaps() {
  dossierFulltextReturnFocus = document.activeElement;
  dossierFulltext.value = {
    label: "下一步核验重点",
    items: allVerificationGaps.value,
  };
  await nextTick();
  const dialog = dossierFulltextDialog.value;
  if (typeof dialog?.showModal === "function" && !dialog.open) {
    dialog.showModal();
  }
  dialog?.focus();
}

// 业务位置：【前端接待室】closeDossierFulltext：切换与 案件卷宗 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function closeDossierFulltext() {
  const dialog = dossierFulltextDialog.value;
  const returnFocus = dossierFulltextReturnFocus;
  dossierFulltextReturnFocus = null;
  if (typeof dialog?.close === "function" && dialog.open) {
    dialog.close();
  }
  dossierFulltext.value = null;
  await nextTick();
  if (returnFocus?.isConnected) {
    returnFocus.focus();
  }
}

// 业务位置：【前端接待室】load：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function load(snapshot = currentWorkspaceSnapshot()) {
  try {
    if (!dispute.value) {
      const loadedDispute = await disputeApi.get(snapshot.actor, snapshot.caseId);
      if (!isCurrentWorkspace(snapshot)) return;
      dispute.value = loadedDispute;
    }
    if (
      intakeStatus.value === null &&
      (props.intakeStatusLoader || props.initialDispute === null)
    ) {
      await refreshIntakeStatus(snapshot);
    }
    admitted.value = currentActorIntakeCompleted.value;
    const privateIntakeReadable = !intakeRecipientView.value;
    if (props.initialMessages === null && privateIntakeReadable) {
      const firstMessages = await refreshMessages(snapshot);
      awaitTemporalInitiatorRunForMessages(firstMessages, snapshot);
      if (shouldRequestIntakeOpening(firstMessages)) {
        await ensureIntakeOpening(snapshot);
      }
    }
    if (
      props.initialTurnMemory === null &&
      props.initialMessages === null &&
      privateIntakeReadable
    ) {
      await refreshTurnMemory(snapshot);
    }
    if (shouldDiscoverActiveIntakeRuns.value && privateIntakeReadable) {
      await resumeActiveIntakeRuns(snapshot);
    }
  } catch (failure) {
    if (!isCurrentWorkspace(snapshot)) return;
    error.value = failure.message;
    agentState.value = "ERROR";
  }
}

// 业务位置：【前端接待室】refreshMessages：重新加载 房间消息和对话记录，确保页面和下一次 Agent 调用基于最新案件版本。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function refreshMessages(snapshot = currentWorkspaceSnapshot()) {
  if (formalReadinessActiveRunId) return messages.value;
  const writeBarrier = roomSnapshotWriteBarrier;
  const refreshToken = ++roomMessagesRefreshToken;
  const loadedMessages = await loadMessages(snapshot);
  if (
    refreshToken === roomMessagesRefreshToken &&
    writeBarrier === roomSnapshotWriteBarrier &&
    !formalReadinessActiveRunId &&
    isCurrentWorkspace(snapshot)
  ) {
    messages.value = loadedMessages;
  }
  return loadedMessages;
}

function shouldRequestIntakeOpening(firstMessages) {
  const respondentStatus = String(
    intakeStatus.value?.respondent_status ||
    intakeStatus.value?.respondentStatus ||
    "",
  ).toUpperCase();
  const openingContextIsEligible =
    (!currentActorIsInitiator.value &&
      partyCanChat.value &&
      respondentStatus === "OPEN") ||
    isTemporalInitiatorOpening();
  return (
    props.initialMessages === null &&
    !historyMode.value &&
    projectionAllowsMessages.value &&
    Array.isArray(firstMessages) &&
    firstMessages.length === 0 &&
    ["USER", "MERCHANT"].includes(actor.role) &&
    openingContextIsEligible &&
    !currentActorIntakeCompleted.value
  );
}

function isTemporalInitiatorOpening() {
  const projection = intakeProcessProjection.value;
  return (
    ["USER", "MERCHANT"].includes(actor.role) &&
    currentActorIsInitiator.value &&
    partyCanChat.value &&
    projection.mode === "CURRENT" &&
    projection.writerMode === "TEMPORAL"
  );
}

function awaitTemporalInitiatorRunForMessages(
  loadedMessages,
  snapshot = currentWorkspaceSnapshot(),
) {
  const projection = intakeProcessProjection.value;
  if (
    props.initialMessages === null &&
    !historyMode.value &&
    Array.isArray(loadedMessages) &&
    loadedMessages.length > 0 &&
    isTemporalInitiatorOpening() &&
    !projection.activeLogicalRunId &&
    !currentActorIntakeCompleted.value
  ) {
    awaitingTemporalInitiatorRun = true;
    scheduleOpeningRunRetry(snapshot);
  }
}

async function ensureIntakeOpening(snapshot = currentWorkspaceSnapshot()) {
  const awaitTemporalInitiatorRun = isTemporalInitiatorOpening();
  const ensure =
    props.openingAction ||
    ((openingActor, openingCaseId, roomType) =>
      roomApi.ensureOpening(openingActor, openingCaseId, roomType));
  const result = await ensure(snapshot.actor, snapshot.caseId, "INTAKE");
  if (!isCurrentWorkspace(snapshot)) return;
  const descriptor = extractAgentRunDescriptor(result);
  if (descriptor) {
    clearOpeningRunRetry();
    awaitingTemporalInitiatorRun = false;
    await consumeIntakeAgentRun(descriptor, snapshot);
  } else if (awaitTemporalInitiatorRun) {
    awaitingTemporalInitiatorRun = true;
  }
  try {
    await refreshMessages(snapshot);
  } finally {
    if (awaitTemporalInitiatorRun && !descriptor && isCurrentWorkspace(snapshot)) {
      scheduleOpeningRunRetry(snapshot);
    }
  }
}

async function refreshMessagesAndRequestIntakeOpening(
  snapshot = currentWorkspaceSnapshot(),
) {
  const refreshedMessages = await refreshMessages(snapshot);
  awaitTemporalInitiatorRunForMessages(refreshedMessages, snapshot);
  if (shouldRequestIntakeOpening(refreshedMessages)) {
    await ensureIntakeOpening(snapshot);
  }
}

// 业务位置：【前端接待室】loadMessages：读取 房间消息和对话记录，并依据当前案件、角色和会话权限裁剪成可用输入。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function loadMessages(snapshot = currentWorkspaceSnapshot()) {
  const loader =
    props.messagesLoader ||
    (() => roomApi.messages(snapshot.actor, snapshot.caseId, "INTAKE"));
  return loader(snapshot);
}

async function loadTurnMemory(snapshot = currentWorkspaceSnapshot()) {
  const loader =
    props.turnMemoryLoader ||
    (() => roomApi.latestTurnMemory(snapshot.actor, snapshot.caseId, "INTAKE"));
  return loader(snapshot);
}

async function loadIntakeStatus(snapshot = currentWorkspaceSnapshot()) {
  const loader =
    props.intakeStatusLoader ||
    (() => disputeApi.intakeStatus(snapshot.actor, snapshot.caseId));
  return loader(snapshot);
}

// 业务位置：【前端接待室】refreshTurnMemory：重新加载 案件会话和上下文快照，确保页面和下一次 Agent 调用基于最新案件版本。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function refreshTurnMemory(snapshot = currentWorkspaceSnapshot()) {
  if (formalReadinessActiveRunId) return turnMemory.value;
  const writeBarrier = roomSnapshotWriteBarrier;
  const refreshToken = ++roomTurnMemoryRefreshToken;
  const loadedMemory = await loadTurnMemory(snapshot);
  if (
    refreshToken === roomTurnMemoryRefreshToken &&
    writeBarrier === roomSnapshotWriteBarrier &&
    !formalReadinessActiveRunId &&
    isCurrentWorkspace(snapshot)
  ) {
    turnMemory.value = loadedMemory;
  }
  return loadedMemory;
}

// 业务位置：【前端接待室】refreshRoomSnapshot：重新加载 页面工作区和业务快照，确保页面和下一次 Agent 调用基于最新案件版本。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function refreshRoomSnapshot(snapshot = currentWorkspaceSnapshot()) {
  if (formalReadinessActiveRunId) return false;
  const writeBarrier = roomSnapshotWriteBarrier;
  const messagesToken = ++roomMessagesRefreshToken;
  const turnMemoryToken = ++roomTurnMemoryRefreshToken;
  const [loadedMessages, loadedMemory] = await Promise.all([
    loadMessages(snapshot),
    loadTurnMemory(snapshot),
  ]);
  if (
    messagesToken !== roomMessagesRefreshToken ||
    turnMemoryToken !== roomTurnMemoryRefreshToken ||
    writeBarrier !== roomSnapshotWriteBarrier ||
    formalReadinessActiveRunId ||
    !isCurrentWorkspace(snapshot)
  ) return false;
  messages.value = loadedMessages;
  turnMemory.value = loadedMemory;
  return true;
}

async function refreshFormalRoomSnapshot(snapshot, context) {
  const writeBarrier = roomSnapshotWriteBarrier;
  const messagesToken = ++roomMessagesRefreshToken;
  const turnMemoryToken = ++roomTurnMemoryRefreshToken;
  const intakeStatusToken = ++roomIntakeStatusRefreshToken;
  const [loadedMessages, loadedMemory, loadedStatus] = await Promise.all([
    loadMessages(snapshot),
    loadTurnMemory(snapshot),
    loadIntakeStatus(snapshot),
  ]);
  if (
    messagesToken !== roomMessagesRefreshToken ||
    turnMemoryToken !== roomTurnMemoryRefreshToken ||
    intakeStatusToken !== roomIntakeStatusRefreshToken ||
    writeBarrier !== roomSnapshotWriteBarrier ||
    !isCurrentFormalReadinessContext(context, snapshot)
  ) return false;
  // Commit the formal result and its command-admission projection together only
  // while this exact run/token still owns the gate. A superseded run must never
  // overwrite the latest run's persisted message, dossier, or status with a
  // late HTTP response.
  messages.value = loadedMessages;
  turnMemory.value = loadedMemory;
  intakeStatus.value = loadedStatus;
  return true;
}

function normalizeAgentRunId(value) {
  return typeof value === "string" && value.trim() ? value.trim() : "";
}

function intakeCaseEventType(event) {
  return String(
    event?.event ||
    event?.eventType ||
    event?.event_type ||
    event?.data?.eventType ||
    event?.data?.event_type ||
    "",
  ).trim().toUpperCase();
}

function intakeCaseEventPayload(event) {
  const envelope = event?.data && typeof event.data === "object" && !Array.isArray(event.data)
    ? event.data
    : event;
  const raw = envelope?.payload_json ?? envelope?.payloadJson;
  if (typeof raw === "string") {
    try {
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }
  return raw && typeof raw === "object" && !Array.isArray(raw) ? raw : null;
}

function nonNegativeInteger(value) {
  const number = Number(value);
  return Number.isInteger(number) && number >= 0 ? number : null;
}

function positiveInteger(value) {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? number : null;
}

function formalReadinessSignal(event) {
  if (intakeCaseEventType(event) !== FORMAL_READINESS_EVENT_TYPE) return null;
  const payload = intakeCaseEventPayload(event);
  if (!payload) return null;
  const requiredFields = [
    "logical_run_id",
    "attempt_id",
    "process_revision",
    "room_revision",
    "room_epoch",
    "fencing_token",
    "command_sequence",
    "event_id",
    "command_admission_state",
  ];
  if (requiredFields.some((field) => !Object.hasOwn(payload, field))) return null;
  const logicalRunId = normalizeAgentRunId(payload.logical_run_id);
  const attemptId = normalizeAgentRunId(payload.attempt_id);
  const eventId = normalizeAgentRunId(payload.event_id);
  const processRevision = positiveInteger(payload.process_revision);
  const roomRevision = positiveInteger(payload.room_revision);
  const roomEpoch = nonNegativeInteger(payload.room_epoch);
  const fencingToken = positiveInteger(payload.fencing_token);
  const commandSequence = positiveInteger(payload.command_sequence);
  if (
    !logicalRunId ||
    !attemptId ||
    !eventId ||
    processRevision === null ||
    roomRevision === null ||
    roomEpoch === null ||
    fencingToken === null ||
    commandSequence === null ||
    String(payload.command_admission_state || "").trim().toUpperCase() !== "READY"
  ) return null;
  return { logicalRunId };
}

function formalMessageAgentRunId(message) {
  if (!message || typeof message !== "object" || Array.isArray(message)) return "";
  const declaredFields = ["agent_run_id", "agentRunId"]
    .filter((field) => Object.hasOwn(message, field));
  if (!declaredFields.length) return "";
  const runIds = declaredFields.map((field) => normalizeAgentRunId(message[field]));
  if (runIds.some((runId) => !runId)) return "";
  return new Set(runIds).size === 1 ? runIds[0] : "";
}

function formalAgentMessageIdentity(message, index, expectedRunId) {
  if (!message || typeof message !== "object") return null;
  const expected = normalizeAgentRunId(expectedRunId);
  if (!expected || formalMessageAgentRunId(message) !== expected) return null;
  const senderRole = String(message.sender_role || message.senderRole || "")
    .trim()
    .toUpperCase();
  const senderType = String(message.sender_type || message.senderType || "")
    .trim()
    .toUpperCase();
  if (senderType !== "AGENT" && !FORMAL_AGENT_SENDER_ROLES.has(senderRole)) {
    return null;
  }
  const id = message.id || message.message_id || message.messageId;
  if (id !== undefined && id !== null && String(id).trim()) {
    return `id:${String(id).trim()}`;
  }
  const sequence = message.sequence_no ?? message.sequenceNo;
  if (sequence !== undefined && sequence !== null) {
    return `sequence:${String(sequence)}:${senderRole}`;
  }
  return `fallback:${index}:${senderRole}:${String(
    message.created_at || message.createdAt || message.message_text || message.messageText || "",
  )}`;
}

function persistedDossierMarker() {
  const envelope = currentCaseDossier.value;
  const dossier = envelope?.dossier;
  if (!isSupportedCaseDetailDossier(dossier)) return null;
  const numberOrNull = (value) => {
    const number = Number(value);
    return Number.isInteger(number) && number >= 0 ? number : null;
  };
  return {
    version: numberOrNull(envelope.dossier_version ?? envelope.dossierVersion),
    sourceTurn: numberOrNull(envelope.source_turn_no ?? envelope.sourceTurnNo),
  };
}

function captureFormalReadinessBaseline(expectedRunId) {
  return {
    agentMessages: new Set(
      (messages.value || [])
        .map((message, index) =>
          formalAgentMessageIdentity(message, index, expectedRunId))
        .filter(Boolean),
    ),
    dossier: persistedDossierMarker(),
  };
}

function formalReadinessVisible(context) {
  if (!isCurrentFormalReadinessContext(context)) return false;
  const baseline = context.baseline;
  const hasNewAgentMessage = (messages.value || []).some((message, index) => {
    const identity = formalAgentMessageIdentity(
      message,
      index,
      context.expectedRunId,
    );
    return identity && !baseline.agentMessages.has(identity);
  });
  const current = persistedDossierMarker();
  if (!hasNewAgentMessage || !current || !targetTemporalCommandAdmissionReady()) return false;
  const hasNewDossier = !baseline.dossier || (
    (
      current.version !== null &&
      (baseline.dossier.version === null || current.version > baseline.dossier.version)
    ) ||
    (
      current.sourceTurn !== null &&
      (
        baseline.dossier.sourceTurn === null ||
        current.sourceTurn > baseline.dossier.sourceTurn
      )
    )
  );
  return hasNewDossier;
}

function targetTemporalCommandAdmissionReady(
  projection = intakeProcessProjection.value,
) {
  return (
    projection.mode === "CURRENT" &&
    projection.writerMode === "TEMPORAL" &&
    projection.commandAdmissionState === "READY"
  );
}

function targetTemporalCommandAdmissionAllowed(
  projection = intakeProcessProjection.value,
) {
  return (
    projection.writerMode !== "TEMPORAL" ||
    targetTemporalCommandAdmissionReady(projection)
  );
}

function isCurrentFormalReadinessContext(context, snapshot = currentWorkspaceSnapshot()) {
  return Boolean(
    context?.requiresFormalReadiness &&
    isCurrentWorkspace(snapshot) &&
    context.expectedRunId &&
    context.expectedRunId === formalReadinessActiveRunId &&
    context.readinessToken === formalReadinessRetryToken,
  );
}

function isCurrentIntakeRunContext(context, snapshot = currentWorkspaceSnapshot()) {
  if (!isCurrentWorkspace(snapshot)) return false;
  return !context?.requiresFormalReadiness ||
    isCurrentFormalReadinessContext(context, snapshot);
}

function consumeFormalReadinessReadySignal(context, snapshot) {
  if (!isCurrentFormalReadinessContext(context, snapshot)) return false;
  if (formalReadinessReadySignalSequence <= context.consumedReadySignalSequence) {
    return false;
  }
  context.consumedReadySignalSequence = formalReadinessReadySignalSequence;
  return true;
}

function wakeFormalReadinessFromEvent(event, snapshot) {
  if (!isCurrentWorkspace(snapshot) || !formalReadinessActiveRunId) return false;
  const signal = formalReadinessSignal(event);
  if (!signal || signal.logicalRunId !== formalReadinessActiveRunId) return false;
  formalReadinessReadySignalSequence += 1;
  formalReadinessRetryWake?.();
  return true;
}

function clearFormalReadinessRetry() {
  roomSnapshotWriteBarrier += 1;
  roomIntakeStatusRefreshToken += 1;
  formalReadinessRetryToken += 1;
  formalReadinessActiveRunId = "";
  formalReadinessReadySignalSequence = 0;
  if (formalReadinessRetryTimer !== null) {
    window.clearTimeout(formalReadinessRetryTimer);
    formalReadinessRetryTimer = null;
  }
  const cancel = formalReadinessRetryCancel;
  formalReadinessRetryCancel = null;
  formalReadinessRetryWake = null;
  cancel?.();
}

function createIntakeRunFinalizationContext(runId) {
  const expectedRunId = normalizeAgentRunId(runId);
  const requiresFormalReadiness = requiresFormalReadinessRetry();
  if (requiresFormalReadiness) {
    // A target Intake run has exactly one authoritative formal result. Starting
    // a new run invalidates any older polling loop before it can touch the
    // shared provisional dossier.
    clearFormalReadinessRetry();
    formalReadinessActiveRunId = expectedRunId;
  }
  return {
    requiresFormalReadiness,
    expectedRunId,
    readinessToken: formalReadinessRetryToken,
    baseline: captureFormalReadinessBaseline(expectedRunId),
    consumedReadySignalSequence: formalReadinessReadySignalSequence,
  };
}

function completeFormalReadinessContext(context, snapshot = currentWorkspaceSnapshot()) {
  if (!isCurrentFormalReadinessContext(context, snapshot)) return false;
  clearFormalReadinessRetry();
  return true;
}

function abortSupersededTemporalIntakeStreams(expectedRunId, snapshot) {
  if (!expectedRunId || !isCurrentWorkspace(snapshot)) return;
  intakeStreamingRuns.value
    .filter((run) => run.runId !== expectedRunId)
    .forEach((run) => abortAgentStream(run.runId));
}

function discardProvisionalIntakeRun(context, snapshot, failure = null) {
  if (!isCurrentIntakeRunContext(context, snapshot)) return false;
  abortAgentStream(context.expectedRunId);
  resetStreamedCaseDetail();
  pendingOriginalStatement.value = "";
  if (context.requiresFormalReadiness) completeFormalReadinessContext(context, snapshot);
  if (failure) {
    error.value = failure.message || "数字人生成失败，已隐藏流式草稿。请稍后重试。";
    agentState.value = "ERROR";
  }
  return true;
}

function formalReadinessRetryDelayMs(baseDelayMs) {
  return Math.max(0, Number(baseDelayMs) || 0);
}

// The durable READY case event is only a wake-up hint. The next exact snapshot
// refresh remains the authority; a low-frequency timer covers missed events.
function waitForFormalReadinessRetry(snapshot, context) {
  if (consumeFormalReadinessReadySignal(context, snapshot)) {
    return Promise.resolve(true);
  }
  const delay = formalReadinessRetryDelayMs(props.formalReadinessPollDelayMs);
  if (delay === 0) return Promise.resolve(isCurrentWorkspace(snapshot));
  return new Promise((resolve) => {
    let settled = false;
    const finish = (ready) => {
      if (settled) return;
      settled = true;
      if (formalReadinessRetryTimer !== null) {
        window.clearTimeout(formalReadinessRetryTimer);
        formalReadinessRetryTimer = null;
      }
      if (formalReadinessRetryCancel === cancel) {
        formalReadinessRetryCancel = null;
      }
      if (formalReadinessRetryWake === wake) {
        formalReadinessRetryWake = null;
      }
      resolve(ready);
    };
    const cancel = () => finish(false);
    const wake = () => finish(consumeFormalReadinessReadySignal(context, snapshot));
    formalReadinessRetryCancel = cancel;
    formalReadinessRetryWake = wake;
    formalReadinessRetryTimer = window.setTimeout(
      () => finish(
        isCurrentFormalReadinessContext(context, snapshot),
      ),
      delay,
    );
  });
}

async function refreshUntilFormalReadiness(snapshot, context) {
  const configuredAttempts = Number(props.formalReadinessPollAttempts);
  const attempts = Number.isFinite(configuredAttempts)
    ? Math.max(1, Math.floor(configuredAttempts))
    : 1;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    if (
      historyMode.value ||
      !isCurrentFormalReadinessContext(context, snapshot)
    ) {
      return false;
    }
    try {
      await refreshFormalRoomSnapshot(snapshot, context);
    } catch (_failure) {
      // A formal commit may become readable after a transient projection miss.
    }
    if (
      !isCurrentFormalReadinessContext(context, snapshot)
    ) {
      return false;
    }
    if (formalReadinessVisible(context)) return true;
    if (attempt < attempts - 1) {
      const shouldContinue = await waitForFormalReadinessRetry(snapshot, context);
      if (!shouldContinue) return false;
    }
  }
  return false;
}

function requiresFormalReadinessRetry() {
  const projection = intakeProcessProjection.value;
  return projection.mode === "CURRENT" && projection.writerMode === "TEMPORAL";
}

async function refreshAfterAgentFinal(snapshot, context) {
  if (!isCurrentIntakeRunContext(context, snapshot)) return false;
  let ready = true;
  try {
    if (context.requiresFormalReadiness) {
      ready = await refreshUntilFormalReadiness(snapshot, context);
    } else {
      await refreshRoomSnapshot(snapshot);
    }
  } catch (failure) {
    if (!isCurrentIntakeRunContext(context, snapshot)) return false;
    discardProvisionalIntakeRun(
      context,
      snapshot,
      failure instanceof Error
        ? failure
        : new Error("正式卷宗同步失败，已隐藏流式草稿。请稍后刷新重试。"),
    );
    return false;
  }
  if (!isCurrentIntakeRunContext(context, snapshot)) return false;
  if (ready) {
    resetStreamedCaseDetail();
    pendingOriginalStatement.value = "";
    if (context.requiresFormalReadiness) {
      completeFormalReadinessContext(context, snapshot);
    }
    agentState.value = "SPEAKING";
  } else {
    // A terminal stream is only a provisional view. Never retain it once the
    // bounded formal-readiness check fails, otherwise a completed AgentRun can
    // make an uncommitted draft look like the authoritative dossier.
    discardProvisionalIntakeRun(context, snapshot);
    error.value = "正式卷宗尚未同步完成，已隐藏流式草稿。请稍后刷新重试。";
    agentState.value = "ERROR";
  }
  return ready;
}

async function consumeIntakeAgentRun(descriptor, snapshot = currentWorkspaceSnapshot()) {
  if (!descriptor || !isCurrentWorkspace(snapshot)) return false;
  const normalizedDescriptor = extractAgentRunDescriptor(descriptor) || descriptor;
  const context = createIntakeRunFinalizationContext(normalizedDescriptor.runId);
  if (context.requiresFormalReadiness) {
    abortSupersededTemporalIntakeStreams(context.expectedRunId, snapshot);
  }
  resetStreamedCaseDetail();
  agentState.value = "STREAMING";
  await consumeAgentRun({
    actor: snapshot.actor,
    caseId: snapshot.caseId,
    roomType: "INTAKE",
    descriptor: normalizedDescriptor,
    agentLabel: "争议接待官",
    senderRole: "INTAKE_OFFICER",
    // The opening/reply bubble is the person-facing first output. Keep the
    // dossier stream behind it so provisional right-side facts never race the
    // answer the user is still reading.
    replyThenBoard: true,
    signal: eventAbortController.signal,
    onEvent: (event) => {
      if (isCurrentIntakeRunContext(context, snapshot)) {
        applyStreamedCaseDetailEvent(event, snapshot);
      }
    },
    onError: (failure) => discardProvisionalIntakeRun(context, snapshot, failure),
    onFinal: () => refreshAfterAgentFinal(snapshot, context),
  });
  return true;
}

async function refreshIntakeStatus(snapshot = currentWorkspaceSnapshot()) {
  const refreshToken = ++roomIntakeStatusRefreshToken;
  const loaded = await loadIntakeStatus(snapshot);
  if (
    refreshToken === roomIntakeStatusRefreshToken &&
    isCurrentWorkspace(snapshot)
  ) {
    intakeStatus.value = loaded;
  }
  return loaded;
}

function clearProjectionStatusRetry({ resetAttempts = true } = {}) {
  if (projectionStatusRetryTimer !== null) {
    window.clearTimeout(projectionStatusRetryTimer);
    projectionStatusRetryTimer = null;
  }
  projectionStatusRetryInFlight = null;
  if (resetAttempts) projectionStatusRetryAttempts = 0;
}

function readinessRetryDelay(attempts) {
  return attempts < READINESS_RETRY_FAST_ATTEMPTS
    ? READINESS_RETRY_FAST_DELAY_MS
    : READINESS_RETRY_SLOW_DELAY_MS;
}

function scheduleProjectionStatusRetry(
  snapshot = currentWorkspaceSnapshot(),
) {
  if (
    componentUnmounted ||
    historyMode.value ||
    !isCurrentWorkspace(snapshot) ||
    intakeProcessProjection.value.mode !== "PROCESSING" ||
    projectionStatusRetryTimer !== null ||
    projectionStatusRetryInFlight !== null
  ) {
    return;
  }
  projectionStatusRetryTimer = window.setTimeout(() => {
    projectionStatusRetryTimer = null;
    void retryProjectionStatus(snapshot);
  }, readinessRetryDelay(projectionStatusRetryAttempts));
}

async function retryProjectionStatus(snapshot) {
  if (
    componentUnmounted ||
    historyMode.value ||
    !isCurrentWorkspace(snapshot) ||
    intakeProcessProjection.value.mode !== "PROCESSING"
  ) {
    return;
  }

  projectionStatusRetryAttempts += 1;
  const request = refreshIntakeStatus(snapshot);
  projectionStatusRetryInFlight = request;
  try {
    await request;
  } catch (_failure) {
    // Projection lag is retried silently; the legacy page error remains untouched.
  } finally {
    if (projectionStatusRetryInFlight === request) {
      projectionStatusRetryInFlight = null;
    }
  }
  if (
    !componentUnmounted &&
    isCurrentWorkspace(snapshot) &&
    !historyMode.value &&
    intakeProcessProjection.value.mode === "PROCESSING"
  ) {
    scheduleProjectionStatusRetry(snapshot);
  }
}

function clearOpeningRunRetry({ resetAttempts = true } = {}) {
  if (openingRunRetryTimer !== null) {
    window.clearTimeout(openingRunRetryTimer);
    openingRunRetryTimer = null;
  }
  openingRunRetryInFlight = null;
  if (resetAttempts) openingRunRetryAttempts = 0;
}

function isTemporalInitiatorAwaitingRun(snapshot) {
  const projection = intakeProcessProjection.value;
  return (
    !componentUnmounted &&
    !historyMode.value &&
    isCurrentWorkspace(snapshot) &&
    !intakeRecipientView.value &&
    awaitingTemporalInitiatorRun &&
    currentActorIsInitiator.value &&
    projection.mode === "CURRENT" &&
    projection.writerMode === "TEMPORAL" &&
    !projection.activeLogicalRunId
  );
}

function scheduleOpeningRunRetry(snapshot = currentWorkspaceSnapshot()) {
  if (
    !isTemporalInitiatorAwaitingRun(snapshot) ||
    openingRunRetryTimer !== null ||
    openingRunRetryInFlight !== null
  ) {
    return;
  }
  openingRunRetryTimer = window.setTimeout(() => {
    openingRunRetryTimer = null;
    void retryOpeningRun(snapshot);
  }, readinessRetryDelay(openingRunRetryAttempts));
}

async function retryOpeningRun(snapshot) {
  if (!isTemporalInitiatorAwaitingRun(snapshot)) return;

  openingRunRetryAttempts += 1;
  const request = refreshIntakeStatus(snapshot);
  openingRunRetryInFlight = request;
  try {
    await request;
  } catch (_failure) {
    // The orchestrator can publish the run after the opening message commits.
  } finally {
    if (openingRunRetryInFlight === request) {
      openingRunRetryInFlight = null;
    }
  }
  if (!isCurrentWorkspace(snapshot)) return;

  if (intakeProcessProjection.value.activeLogicalRunId) {
    const resumed = await resumeActiveIntakeRuns(snapshot);
    if (resumed) {
      awaitingTemporalInitiatorRun = false;
      return;
    }
  }
  if (currentActorIntakeCompleted.value) {
    awaitingTemporalInitiatorRun = false;
    return;
  }
  scheduleOpeningRunRetry(snapshot);
}

function intakeStatusAllowsEvidence(status) {
  const projection = normalizeIntakeProcessProjection(status);
  return Boolean(
    (
      projection.mode === "LEGACY" ||
      (projection.mode === "CURRENT" && projection.roomPhase === "COMPLETED")
    ) &&
    (status?.can_enter_evidence ?? status?.canEnterEvidence),
  );
}

function waitForEvidenceStatus(delayMs) {
  if (delayMs <= 0) return Promise.resolve();
  return new Promise((resolve) => window.setTimeout(resolve, delayMs));
}

async function verifyEvidenceReady(snapshot = currentWorkspaceSnapshot()) {
  const attempts = Math.max(1, props.evidenceReadyPollAttempts);
  let lastFailure = null;
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    if (!isCurrentWorkspace(snapshot)) return false;
    try {
      const status = await refreshIntakeStatus(snapshot);
      if (!isCurrentWorkspace(snapshot)) return false;
      if (intakeStatusAllowsEvidence(status)) return true;
      lastFailure = null;
    } catch (failure) {
      lastFailure = failure;
    }
    if (attempt < attempts - 1) {
      await waitForEvidenceStatus(props.evidenceReadyPollDelayMs);
    }
  }
  if (lastFailure) throw lastFailure;
  return false;
}

function resetStreamedCaseDetail() {
  streamedCaseDetailSections.value = {};
}

function applyStreamedCaseDetailEvent(event, snapshot = currentWorkspaceSnapshot()) {
  if (!isCurrentWorkspace(snapshot)) return;
  // A V2 abort and its replacement reset are delivered as separate durable
  // events. Clear the provisional overlay at the abort boundary so a lost or
  // delayed reset cannot leave facts from the failed attempt on screen. This
  // only removes streamed sections; the persisted dossier remains the base
  // rendered by caseDetailDossier.
  if (event?.event === "attempt_aborted" || event?.event === "attempt_reset") {
    resetStreamedCaseDetail();
    return;
  }
  if (event?.event !== "visible_delta") return;
  const prefix = "case_detail.";
  const fieldPath = String(event.fieldPath || "");
  if (!fieldPath.startsWith(prefix) || !event.delta) return;
  const [section, ...propertyPath] = fieldPath.slice(prefix.length).split(".");
  if (!section) return;
  try {
    if (propertyPath.length) {
      const previousSection = streamedCaseDetailSections.value[section];
      const nextSection =
        previousSection && typeof previousSection === "object" && !Array.isArray(previousSection)
          ? { ...previousSection }
          : {};
      let target = nextSection;
      propertyPath.forEach((property, index) => {
        if (index === propertyPath.length - 1) {
          target[property] = String(target[property] || "") + event.delta;
          return;
        }
        const child = target[property];
        target[property] =
          child && typeof child === "object" && !Array.isArray(child) ? { ...child } : {};
        target = target[property];
      });
      streamedCaseDetailSections.value = {
        ...streamedCaseDetailSections.value,
        [section]: nextSection,
      };
      return;
    }
    // Root branches are atomic one-event JSON snapshots. Do not merge them
    // into an earlier provisional branch: the terminal snapshot must replace
    // that branch. Chunked root JSON needs a protocol extension rather than
    // client-side reassembly.
    const value = JSON.parse(event.delta);
    streamedCaseDetailSections.value = {
      ...streamedCaseDetailSections.value,
      [section]: value,
    };
  } catch (_failure) {
    // 结构化分区只接受完整 JSON；最终事件仍会刷新正式持久化卷宗。
  }
}

function explicitActiveRunDescriptor(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const candidates = [
    value,
    value.agent_run,
    value.agentRun,
    value.accepted_run,
    value.acceptedRun,
    value.run,
    value.stream,
    value.room_message,
    value.roomMessage,
  ];
  const candidate = candidates.find((item) =>
    item &&
    typeof item === "object" &&
    !Array.isArray(item) &&
    [item.run_id, item.runId, item.agent_run_id, item.agentRunId].some(Boolean),
  );
  if (!candidate) return null;

  const runIds = [
    candidate.run_id,
    candidate.runId,
    candidate.agent_run_id,
    candidate.agentRunId,
  ].filter((runId) => runId !== undefined && runId !== null);
  if (
    !runIds.length ||
    runIds.some((runId) => typeof runId !== "string" || !runId.trim())
  ) {
    return null;
  }
  const normalizedRunIds = new Set(runIds.map((runId) => runId.trim()));
  if (normalizedRunIds.size !== 1) return null;

  const hasSnakeCaseUrl = Object.hasOwn(candidate, "stream_url");
  const hasCamelCaseUrl = Object.hasOwn(candidate, "streamUrl");
  if (!hasSnakeCaseUrl && !hasCamelCaseUrl) return null;
  if (
    hasSnakeCaseUrl &&
    hasCamelCaseUrl &&
    candidate.stream_url !== candidate.streamUrl
  ) {
    return null;
  }
  const streamUrl = hasSnakeCaseUrl ? candidate.stream_url : candidate.streamUrl;
  if (typeof streamUrl !== "string" || !streamUrl.trim()) return null;
  return { descriptor: value, runId: [...normalizedRunIds][0] };
}

// 业务位置：【前端接待室】resumeActiveIntakeRuns：执行 案件受理信息和接待结论 对应的业务动作，并将结果交给 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function resumeActiveIntakeRuns(snapshot = currentWorkspaceSnapshot()) {
  const projection = intakeProcessProjection.value;
  if (
    historyMode.value ||
    !isCurrentWorkspace(snapshot) ||
    projection.mode === "PROCESSING" ||
    actorPartyPosition.value === "OBSERVER" ||
    intakeRecipientView.value
  ) {
    return false;
  }
  if (projection.mode === "CURRENT" && !projection.activeLogicalRunId) return false;
  if (
    projection.mode === "CURRENT" &&
    intakeStreamingRuns.value.some((run) =>
      run.runId !== projection.activeLogicalRunId,
    )
  ) {
    abortSupersededTemporalIntakeStreams(
      projection.activeLogicalRunId,
      snapshot,
    );
  }

  const activeRuns = await loadActiveAgentRuns(
    snapshot.actor,
    snapshot.caseId,
    "INTAKE",
  );
  const latestProjection = intakeProcessProjection.value;
  if (
    !isCurrentWorkspace(snapshot) ||
    !Array.isArray(activeRuns) ||
    latestProjection.mode !== projection.mode ||
    (
      projection.mode === "CURRENT" &&
      latestProjection.activeLogicalRunId !== projection.activeLogicalRunId
    )
  ) {
    return false;
  }
  const projectedRun = latestProjection.mode === "CURRENT"
    ? activeRuns
      .map(explicitActiveRunDescriptor)
      .find((descriptor) =>
        descriptor?.runId === latestProjection.activeLogicalRunId,
      )
    : null;
  const recoverableRuns = latestProjection.mode === "CURRENT"
    ? projectedRun ? [projectedRun.descriptor] : []
    : activeRuns;
  if (!recoverableRuns.length) return false;
  const requiresFormalReadiness = requiresFormalReadinessRetry();
  if (requiresFormalReadiness && recoverableRuns.length !== 1) {
    clearFormalReadinessRetry();
    resetStreamedCaseDetail();
    error.value = "检测到多个接待生成任务，已停止展示草稿。请刷新后重试。";
    agentState.value = "ERROR";
    return false;
  }
  const recoveryPlans = recoverableRuns.map((descriptor) => {
    const normalizedDescriptor = extractAgentRunDescriptor(descriptor) || descriptor;
    return {
      descriptor: normalizedDescriptor,
      context: createIntakeRunFinalizationContext(normalizedDescriptor.runId),
    };
  });
  resetStreamedCaseDetail();
  agentState.value = "STREAMING";
  await Promise.all(recoveryPlans.map(({ descriptor, context }) => consumeAgentRun({
    actor: snapshot.actor,
    caseId: snapshot.caseId,
    roomType: "INTAKE",
    descriptor,
    agentLabel: "争议接待官",
    senderRole: "INTAKE_OFFICER",
    replyThenBoard: true,
    signal: eventAbortController.signal,
    onEvent: (event) => {
      if (isCurrentIntakeRunContext(context, snapshot)) {
        applyStreamedCaseDetailEvent(event, snapshot);
      }
    },
    onError: (failure) => discardProvisionalIntakeRun(context, snapshot, failure),
    onFinal: () => refreshAfterAgentFinal(snapshot, context),
  })));
  if (
    isCurrentWorkspace(snapshot) &&
    intakeProcessProjection.value.mode !== "PROCESSING" &&
    agentState.value !== "ERROR"
  ) {
    agentState.value = "SPEAKING";
  }
  return true;
}

// 业务位置：【前端接待室】fetchModelHealth：读取 模型请求和结构化结果，并依据当前案件、角色和会话权限裁剪成可用输入。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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

// 业务位置：【前端接待室】checkModelConnection：围绕 模型请求和结构化结果 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
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
        payload?.ready === true || status === "CONNECTED" || status === "UP"
          ? "connected"
          : "disconnected";
    } catch (_failure) {
      modelConnectionState.value = "disconnected";
    } finally {
      modelHealthInFlight = null;
    }
  })();
  return modelHealthInFlight;
}

// 业务位置：【前端接待室】startModelHealthPolling：启动或关闭与 模型请求和结构化结果 相关的后台任务或订阅，控制运行资源和生命周期。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function startModelHealthPolling() {
  if (historyMode.value || modelHealthTimer) return;
  void checkModelConnection();
  modelHealthTimer = window.setInterval(() => {
    void checkModelConnection();
  }, 30000);
}

// 业务位置：【前端接待室】stopModelHealthPolling：启动或关闭与 模型请求和结构化结果 相关的后台任务或订阅，控制运行资源和生命周期。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function stopModelHealthPolling() {
  if (!modelHealthTimer) return;
  window.clearInterval(modelHealthTimer);
  modelHealthTimer = null;
}

// 业务位置：【前端接待室】nextLocalSequenceNo：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 房间消息、初始表单和接待 Agent 流 正确进入 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function nextLocalSequenceNo() {
  return Math.max(0, ...messages.value.map((message) => message.sequence_no || 0)) + 1;
}

// 业务位置：【前端接待室】appendOptimisticPartyMessage：更新 当事人主张、角色和对方态度 的消息、缓存或持久记录，避免旧回合数据影响当前处理。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function appendOptimisticPartyMessage(command, snapshot = currentWorkspaceSnapshot()) {
  if (!command?.text?.trim()) return "";
  pendingOriginalStatement.value = command.text.trim();
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

// 业务位置：【前端接待室】removeOptimisticMessage：更新 房间消息和对话记录 的消息、缓存或持久记录，避免旧回合数据影响当前处理。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function removeOptimisticMessage(id) {
  if (!id) return;
  messages.value = messages.value.filter((message) => message.id !== id);
  pendingOriginalStatement.value = "";
}

// 业务位置：【前端接待室】startEventStream：启动或关闭与 Agent 流事件 相关的后台任务或订阅，控制运行资源和生命周期。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function startEventStream(snapshot = currentWorkspaceSnapshot()) {
  if (historyMode.value || !isCurrentWorkspace(snapshot)) return false;
  const streamer = props.eventStreamer || streamRoomEvents;
  if (!props.eventStreamer) {
    try {
      await primeRoomEventCursor({
        actor: snapshot.actor,
        caseId: snapshot.caseId,
        roomType: "INTAKE",
        state: eventState,
      });
    } catch (failure) {
      if (isCurrentWorkspace(snapshot)) {
        eventState.connected = false;
        eventState.streamError = failure;
      }
    }
    if (!isCurrentWorkspace(snapshot)) return false;
  }
  void streamer({
    actor: snapshot.actor,
    caseId: snapshot.caseId,
    roomType: "INTAKE",
    state: eventState,
    signal: eventAbortController.signal,
    snapshotLoader: () => refreshRoomSnapshot(snapshot),
    applyEvent: async (event) => {
      if (!isCurrentWorkspace(snapshot)) return;
      const eventType = intakeCaseEventType(event);
      if (eventType === FORMAL_READINESS_EVENT_TYPE) {
        wakeFormalReadinessFromEvent(event, snapshot);
        return;
      }
      if (eventType === "RESPONDENT_CONFIRMED") {
        const evidenceReady = await verifyEvidenceReady(snapshot);
        if (evidenceReady && isCurrentWorkspace(snapshot)) {
          await router.push(`/disputes/${snapshot.caseId}/evidence`);
        }
        return;
      }
      if (eventType === "EVIDENCE_OPENED") {
        if (props.initialIntakeStatus === null && props.initialDispute === null) {
          await refreshIntakeStatus(snapshot);
        }
        if (canEnterEvidence.value) {
          await router.push(`/disputes/${snapshot.caseId}/evidence`);
        } else if (partyCanChat.value) {
          await Promise.all([
            refreshMessages(snapshot),
            refreshTurnMemory(snapshot),
          ]);
        }
      }
    },
  });
  return true;
}

// 业务位置：【前端接待室】postMessage：执行 房间消息和对话记录 对应的业务动作，并将结果交给 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function postMessage(command) {
  if (historyMode.value || !projectionAllowsMessages.value) return;
  const snapshot = currentWorkspaceSnapshot();
  if (!modelConnected.value) {
    await checkModelConnection();
    if (!modelConnected.value) {
      error.value = "数字人未连接，模型服务恢复后才能继续提交陈述。";
      agentState.value = "ERROR";
      return;
    }
  }
  if (!isCurrentWorkspace(snapshot) || !projectionAllowsMessages.value) return;
  agentState.value = "THINKING";
  submitting.value = true;
  error.value = "";
  const optimisticId = appendOptimisticPartyMessage(command, snapshot);
  try {
    const submit =
      props.postMessageAction ||
      ((payload) => roomApi.postMessage(snapshot.actor, snapshot.caseId, "INTAKE", payload));
    const result = await submit(command);
    const descriptor = extractAgentRunDescriptor(result);
    let finalizationContext = null;
    if (descriptor) {
      finalizationContext = createIntakeRunFinalizationContext(descriptor.runId);
      if (finalizationContext.requiresFormalReadiness) {
        abortSupersededTemporalIntakeStreams(
          finalizationContext.expectedRunId,
          snapshot,
        );
      }
      resetStreamedCaseDetail();
      agentState.value = "STREAMING";
      await consumeAgentRun({
        actor: snapshot.actor,
        caseId: snapshot.caseId,
        roomType: "INTAKE",
        descriptor,
        agentLabel: "争议接待官",
        senderRole: "INTAKE_OFFICER",
        replyThenBoard: true,
        signal: eventAbortController.signal,
        onEvent: (event) => {
          if (isCurrentIntakeRunContext(finalizationContext, snapshot)) {
            applyStreamedCaseDetailEvent(event, snapshot);
          }
        },
        onError: (failure) =>
          discardProvisionalIntakeRun(finalizationContext, snapshot, failure),
        onFinal: () => refreshAfterAgentFinal(snapshot, finalizationContext),
      });
    } else {
      await refreshRoomSnapshot(snapshot);
      resetStreamedCaseDetail();
      pendingOriginalStatement.value = "";
    }
    if (
      isCurrentWorkspace(snapshot) &&
      agentState.value !== "ERROR" &&
      (
        !finalizationContext ||
        !finalizationContext.requiresFormalReadiness ||
        isCurrentFormalReadinessContext(finalizationContext, snapshot)
      )
    ) {
      agentState.value = "SPEAKING";
    }
  } catch (failure) {
    if (!isCurrentWorkspace(snapshot)) return;
    removeOptimisticMessage(optimisticId);
    resetStreamedCaseDetail();
    error.value = failure.message;
    agentState.value = "ERROR";
    void checkModelConnection();
  } finally {
    if (isCurrentWorkspace(snapshot)) {
      submitting.value = false;
    }
  }
}

// 业务位置：【前端接待室】resolveWithoutDispute：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function resolveWithoutDispute() {
  if (
    historyMode.value ||
    !targetTemporalCommandAdmissionAllowed() ||
    intakeCancellationDisabled.value
  ) return;
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
    disputeStore.list.data = disputeStore.list.data.filter(
      (item) => item.id !== snapshot.caseId,
    );
    if (disputeStore.current.data?.id === snapshot.caseId) {
      disputeStore.current.data = null;
      disputeStore.current.status = "empty";
    }
    await router.replace("/disputes");
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

// 业务位置：【前端接待室】confirmAdmission：执行 案件受理信息和接待结论 对应的业务动作，并将结果交给 案件卷宗展示、确认受理或进入证据室。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function confirmAdmission() {
  if (
    historyMode.value ||
    !targetTemporalCommandAdmissionAllowed() ||
    intakeDossierSubmissionDisabled.value
  ) return;
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
    const result = await confirm(command);
    if (!isCurrentWorkspace(snapshot)) return;
    if (result) {
      intakeStatus.value = {
        ...(intakeStatus.value || {}),
        current_actor_completed: true,
        can_use_intake: false,
        can_enter_evidence: false,
        evidence_deadline_at: result.deadline_at || result.deadlineAt,
      };
      admitted.value = true;
      agentState.value = "HANDOFF";
      const evidenceReady = await verifyEvidenceReady(snapshot);
      if (evidenceReady && isCurrentWorkspace(snapshot)) {
        await router.push(`/disputes/${snapshot.caseId}/evidence`);
      }
    }
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

// 业务位置：【前端接待室】enterEvidenceRoom：切换与 当前可见证据和附件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
async function enterEvidenceRoom() {
  if (historyMode.value || !canEnterEvidence.value) return;
  await router.push(`/disputes/${caseId.value}/evidence`);
}

// 业务位置：【前端接待室】dismissError：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：房间消息、初始表单和接待 Agent 流。下游：案件卷宗展示、确认受理或进入证据室。边界：前端仅展示建议，不能自行确认责任。
function dismissError() {
  error.value = "";
  if (agentState.value === "ERROR") {
    agentState.value = "LISTENING";
  }
}

onMounted(async () => {
  const snapshot = currentWorkspaceSnapshot();
  if (!historyMode.value) startModelHealthPolling();
  if (!historyMode.value && (props.eventStreamer || props.initialMessages === null)) {
    await startEventStream(snapshot);
    if (!isCurrentWorkspace(snapshot)) return;
  }
  await load(snapshot);
});
watch(
  () => [caseId.value, actor.id, actor.role],
  async () => {
    resetWorkspaceForActorChange();
    const snapshot = currentWorkspaceSnapshot();
    if (!historyMode.value && (props.eventStreamer || props.initialMessages === null)) {
      await startEventStream(snapshot);
      if (!isCurrentWorkspace(snapshot)) return;
    }
    await load(snapshot);
  },
);
watch(
  () => intakeProcessProjection.value.mode,
  (mode, previousMode) => {
    if (mode === "PROCESSING") {
      if (previousMode !== "PROCESSING") {
        projectionStatusRetryAttempts = 0;
        clearOpeningRunRetry();
        clearAgentStreams({
          caseId: caseId.value,
          roomType: "INTAKE",
          actorId: actor.id,
          actorRole: actor.role,
        });
        resetStreamedCaseDetail();
        if (agentState.value === "STREAMING") agentState.value = "LISTENING";
      }
      scheduleProjectionStatusRetry();
      return;
    }

    clearProjectionStatusRetry();
    if (mode !== "CURRENT") {
      clearOpeningRunRetry();
      awaitingTemporalInitiatorRun = false;
    } else if (awaitingTemporalInitiatorRun) {
      scheduleOpeningRunRetry(currentWorkspaceSnapshot());
    }
    if (
      previousMode === "PROCESSING" &&
      ["CURRENT", "LEGACY"].includes(mode) &&
      shouldDiscoverActiveIntakeRuns.value &&
      actorPartyPosition.value !== "OBSERVER" &&
      !intakeRecipientView.value
    ) {
      void resumeActiveIntakeRuns(currentWorkspaceSnapshot());
    }
    if (
      previousMode === "PROCESSING" &&
      mode === "CURRENT" &&
      props.initialMessages === null &&
      !intakeRecipientView.value
    ) {
      if (awaitingTemporalInitiatorRun) {
        scheduleOpeningRunRetry(currentWorkspaceSnapshot());
      } else {
        void refreshMessagesAndRequestIntakeOpening(currentWorkspaceSnapshot());
      }
    }
  },
  { immediate: true },
);
watch(historyMode, (historical) => {
  if (!historical) {
    startModelHealthPolling();
    scheduleProjectionStatusRetry();
    if (props.eventStreamer || props.initialMessages === null) {
      void startEventStream(currentWorkspaceSnapshot());
    }
    return;
  }
  stopModelHealthPolling();
  clearProjectionStatusRetry();
  clearOpeningRunRetry();
  clearFormalReadinessRetry();
  awaitingTemporalInitiatorRun = false;
  workspaceGeneration.value += 1;
  submitting.value = false;
  eventAbortController.abort();
  eventAbortController = new AbortController();
  eventState.connected = false;
  eventState.reconnecting = false;
  clearAgentStreams({ caseId: caseId.value, roomType: "INTAKE" });
});
onBeforeUnmount(() => {
  componentUnmounted = true;
  clearProjectionStatusRetry();
  clearOpeningRunRetry();
  clearFormalReadinessRetry();
  awaitingTemporalInitiatorRun = false;
  stopModelHealthPolling();
  eventAbortController.abort();
  clearAgentStreams({ caseId: caseId.value, roomType: "INTAKE" });
  const dialog = dossierFulltextDialog.value;
  if (typeof dialog?.close === "function" && dialog.open) {
    dialog.close();
  }
  dossierFulltextReturnFocus = null;
});
</script>

<template>
  <RoomShell
    eyebrow="INTAKE LOUNGE"
    title="争议接待室"
    subtitle="案情接待"
    subtitle-description="请完整说明争议经过、当前状态和处理诉求，接待官会同步整理案情展板。"
    :case-id="caseId"
    :connection-state="connectionState"
    :show-case-id="false"
    :show-connection="false"
    :show-boundary="false"
    :history-mode="historyMode"
    history-description="接待记录已经封存，对话、受理、取消和流程推进均已锁定；你仍可浏览当时的案情卷宗。"
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
        <div
          class="intake-room__case-note"
          :data-status="intakeWorkStatusCopy.tone"
          data-intake-work-status
        >
          <i class="intake-room__status-orb" aria-hidden="true" />
          <div class="intake-room__status-copy">
            <span>{{ intakeWorkStatusCopy.eyebrow }}</span>
            <h2>{{ intakeWorkStatusCopy.title }}</h2>
            <p>{{ intakeWorkStatusCopy.description }}</p>
          </div>
          <div class="intake-room__status-meta">
            <small :data-model-state="intakeStreamingRuns.length ? 'connected' : modelConnectionState">
              {{ modelConnectionLabel }}
            </small>
          </div>
        </div>
        <div
          class="intake-room__conversation-lock-frame"
          :class="{ 'intake-room__conversation-lock-frame--locked': intakeRecipientView }"
        >
          <ConversationStream
            :messages="intakeRecipientView ? [] : messages"
            :streaming-runs="historyMode || intakeRecipientView ? [] : intakeStreamingRuns"
            :disabled="historyMode || submitting || intakeStreamingRuns.length > 0 || admitted || !partyCanChat || !modelConnected"
            :composer-visible="intakeComposerVisible"
            :composer-hint="intakeComposerHint"
            :disabled-reason="intakeComposerDisabledReason"
            :empty-text="intakeConversationEmptyText"
            placeholder="补充订单、物流、双方沟通或你的期望…"
            @submit="postMessage"
          />
          <div
            v-if="intakeRecipientView"
            class="intake-room__locked-chat"
            data-intake-locked-chat
            aria-label="接待会话尚未开放"
          >
            <span aria-hidden="true">🔒</span>
            <strong>你的接待会话尚未开放</strong>
            <p>发起方完成案情接待后，这里会开放你的私有会话。双方私聊原文互不可见，右侧仅交接结构化案情。</p>
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
                  <span>{{ visibleClaimStatus.claimLabel }}</span>
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
                  <span>{{ visibleClaimStatus.responseLabel }}</span>
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
            v-if="historyMode"
            class="intake-dossier__readonly-actions"
            data-intake-history-actions
          >
            历史接待已锁定，仅保留当时的案情与对话记录
          </div>
          <div
            v-else-if="intakeRecipientView"
            class="intake-dossier__readonly-actions"
            data-intake-waiting-for-initiator
          >
            等待发起方完成接待
          </div>
          <div
            v-else-if="admitted && canEnterEvidence"
            class="intake-dossier__actions"
          >
            <button type="button" data-enter-evidence-room @click="enterEvidenceRoom">
              进入证据室
            </button>
          </div>
          <div
            v-else-if="admitted && currentActorIsInitiator"
            class="intake-dossier__readonly-actions"
            data-waiting-for-respondent-intake
          >
            本方陈述已完成，等待对方独立完善陈述；双方完成后将统一开放证据室
          </div>
          <div
            v-else-if="admitted"
            class="intake-dossier__readonly-actions"
            data-waiting-for-evidence-ready
          >
            双方完成状态正在核验，证据室确认开放后将自动进入
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
              :disabled="intakeDossierSubmissionDisabled"
              @click="confirmAdmission"
            >
              <span v-if="admitted">已上报</span>
              <span v-else-if="submitting">正在整理…</span>
              <span v-else>{{ confirmButtonCopy }}</span>
            </button>
            <button
              v-if="currentActorIsInitiator"
              type="button"
              class="intake-dossier__secondary"
              data-resolve-without-dispute
              :disabled="intakeCancellationDisabled"
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
    <dialog
      v-if="dossierFulltext"
      ref="dossierFulltextDialog"
      class="intake-fulltext-dialog"
      data-dossier-fulltext-dialog
      role="dialog"
      aria-modal="true"
      aria-labelledby="intake-fulltext-title"
      tabindex="-1"
      @cancel.prevent="closeDossierFulltext"
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
    </dialog>
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
  grid-template-rows: 92px minmax(0, 1fr);
  min-height: 0;
  padding: 18px;
}
.intake-dossier {
  display: grid;
  grid-template-rows: 60px minmax(0, 1fr) 52px;
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
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  height: 92px;
  padding: 15px 16px 18px;
  margin: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 20% 15%, rgba(255, 255, 255, .95), transparent 34%),
    linear-gradient(135deg, #f8fbff, #f4f7ff);
  border: 1px solid #dce8f4;
  border-radius: 18px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .92);
}
.intake-room__case-note span,
.intake-dossier header span {
  color: #7186aa;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .16em;
}
.intake-room__status-orb {
  position: relative;
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 14px;
  background: linear-gradient(135deg, #8ca2ff, #77dfb7);
  box-shadow: 0 10px 24px rgba(96, 122, 180, .22);
}
.intake-room__status-orb::before,
.intake-room__status-orb::after {
  content: "";
  position: absolute;
  border-radius: 999px;
}
.intake-room__status-orb::before {
  width: 11px;
  height: 11px;
  background: #fff;
}
.intake-room__status-orb::after {
  inset: -5px;
  border: 1px solid rgba(126, 151, 232, .38);
  animation: intake-status-pulse 1.55s ease-out infinite;
}
.intake-room__status-copy {
  min-width: 0;
}
.intake-room__case-note h2 {
  margin: 3px 0 2px;
  color: #34435c;
  font-size: 17px;
  line-height: 1.22;
}
.intake-room__case-note p {
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
.intake-room__status-meta {
  display: grid;
  min-width: 96px;
  justify-items: end;
  gap: 4px;
}
.intake-room__status-meta small {
  padding: 3px 8px;
  color: #71809a;
  background: rgba(255, 255, 255, .72);
  border: 1px solid #dfe8f4;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
}
.intake-room__status-meta small[data-model-state="connected"] {
  color: #2f8569;
  background: rgba(229, 250, 240, .82);
  border-color: rgba(106, 211, 169, .48);
}
.intake-room__status-meta small[data-model-state="checking"] {
  color: #6b6f9a;
  background: rgba(241, 244, 255, .86);
  border-color: rgba(163, 174, 240, .5);
}
.intake-room__status-meta small[data-model-state="disconnected"] {
  color: #b24b5d;
  background: rgba(255, 238, 240, .9);
  border-color: rgba(244, 143, 156, .55);
}
.intake-room__case-note[data-status="working"] .intake-room__status-orb {
  background: linear-gradient(135deg, #a98cf5, #79b9ff);
}
.intake-room__case-note[data-status="streaming"] .intake-room__status-orb {
  background: linear-gradient(135deg, #ff9c80, #a98cf5);
}
.intake-room__case-note[data-status="ready"] .intake-room__status-orb {
  background: linear-gradient(135deg, #64d8a4, #70c7ff);
}
.intake-room__case-note[data-status="handoff"] .intake-room__status-orb {
  background: linear-gradient(135deg, #74a7ff, #b7c4da);
}
.intake-room__case-note[data-status="error"] .intake-room__status-orb {
  background: linear-gradient(135deg, #ff7f8d, #ffbd8a);
}
.intake-room__case-note[data-status="ready"] .intake-room__status-orb::after,
.intake-room__case-note[data-status="handoff"] .intake-room__status-orb::after,
.intake-room__case-note[data-status="error"] .intake-room__status-orb::after {
  animation: none;
  opacity: .35;
}
@keyframes intake-status-pulse {
  0% {
    opacity: .85;
    transform: scale(.86);
  }
  100% {
    opacity: 0;
    transform: scale(1.28);
  }
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
  height: 60px;
  min-width: 0;
  overflow: hidden;
}
.intake-dossier header h2 {
  margin: 5px 0 0;
  color: #34435c;
  font-size: 23px;
  line-height: 1.2;
}
.intake-dossier header small { color: #7384a1; }
.intake-case-detail {
  display: grid;
  grid-template-rows: 44px 412px 96px;
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
  align-content: start;
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
  grid-template-rows: 18px 110px 126px 94px;
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
  height: 126px;
  grid-template-rows: 84px 42px;
  gap: 0;
  min-height: 0;
  border-top: 1px dashed #dce8f3;
}
.intake-case-detail__fields {
  display: grid;
  height: 84px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  min-width: 0;
}
.intake-case-detail__field {
  display: grid;
  box-sizing: border-box;
  height: 84px;
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
  -webkit-line-clamp: 3;
  max-height: 4.5em;
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
  height: 96px;
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
  display: block;
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  height: 94px;
  min-height: 94px;
  grid-template-rows: 16px minmax(0, 1fr);
  gap: 2px;
  padding: 2px 0 0;
  overflow: hidden;
  background: transparent;
  border: 0;
  border-top: 0;
  border-radius: 0;
}
@supports (-webkit-line-clamp: 1) {
  .intake-case-detail__field strong {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
  }

  .intake-case-detail__field strong {
    -webkit-line-clamp: 3;
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
  width: 100dvw;
  max-width: none;
  height: 100dvh;
  max-height: none;
  margin: 0;
  border: 0;
  box-sizing: border-box;
  background: transparent;
}
.intake-fulltext-dialog[open] {
  display: grid;
  place-items: center;
  padding: 16px;
}
.intake-fulltext-dialog::backdrop {
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
  min-width: 0;
  margin: 0;
  color: #6d7890;
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  word-break: break-word;
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
