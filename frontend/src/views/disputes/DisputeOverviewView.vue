<!--
  文件作用：前端页面视图文件，组织售后争议对应页面的数据加载、交互和展示。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { extractAgentRunDescriptor } from "../../api/agentStream";
import { disputeApi } from "../../api/disputes";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import AgentStreamErrorDialog from "../../components/room/AgentStreamErrorDialog.vue";
import AgentStreamingMessage from "../../components/room/AgentStreamingMessage.vue";
import PhaseCountdown from "../../components/room/PhaseCountdown.vue";
import { actor } from "../../state/actor";
import {
  disputeStore,
  loadDisputes,
} from "../../stores/dispute";
import {
  activeAgentStreams,
  clearAgentStreams,
  consumeAgentRun,
} from "../../stores/agentStream";

const props = defineProps({
  initialCases: { type: Array, default: () => [] },
  serverNow: { type: String, default: () => new Date().toISOString() },
  createAction: { type: Function, default: null },
  simulateExternalImportAction: { type: Function, default: null },
  deleteSimulatedCaseAction: { type: Function, default: null },
});

const router = useRouter();
const localCases = ref([...props.initialCases]);
const selectedId = ref(props.initialCases[0]?.id || null);
const intakeOpen = ref(false);
const creating = ref(false);
const importingExternal = ref(false);
const deletingCase = ref(false);
const openingStage = ref("");
const stageNavigationError = ref("");
const reviewPermissionOpen = ref(false);
const deleteCandidate = ref(null);
const createError = ref("");
const importError = ref("");
const importStreamError = ref("");
const deleteError = ref("");
const intakeForm = ref({
  orderReference: "",
  afterSalesReference: "",
  logisticsReference: "",
  userId: "",
  merchantId: "",
  requestedResolution: "VERIFY_OR_EXPLAIN_ONLY",
  requestedAmount: "",
  requestedItems: "",
  description: "",
});

const claimResolutionOptions = [
  { value: "REFUND", label: "退款" },
  { value: "RETURN_REFUND", label: "退货退款" },
  { value: "RESHIP", label: "补发" },
  { value: "REPLACE_OR_REPAIR", label: "换货 / 维修" },
  { value: "COMPENSATION", label: "赔付" },
  { value: "CANCEL_ORDER", label: "取消订单" },
  { value: "VERIFY_OR_EXPLAIN_ONLY", label: "仅要求核验 / 解释" },
  { value: "OTHER", label: "其他" },
];

const cases = computed(() =>
  localCases.value.length ? localCases.value : disputeStore.list.data,
);

// 业务位置：【前端案件页面】reconcileCases：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function reconcileCases(nextCases) {
  const refreshed = Array.isArray(nextCases) ? [...nextCases] : [];
  localCases.value = refreshed;
  if (!refreshed.some((item) => item.id === selectedId.value)) {
    selectedId.value = refreshed[0]?.id || null;
  }
}

watch(
  () => disputeStore.list.updatedAt,
  () => {
    if (props.initialCases.length) return;
    reconcileCases(disputeStore.list.data);
  },
);
watch(selectedId, () => {
  stageNavigationError.value = "";
  reviewPermissionOpen.value = false;
});
const selectedCase = computed(
  () =>
    cases.value.find((item) => item.id === selectedId.value) ||
    cases.value[0] ||
    null,
);
const casePartyRoles = new Set(["USER", "MERCHANT"]);
const humanReviewStatuses = new Set(["REVIEW_PENDING", "WAITING_HUMAN_REVIEW"]);
const outcomeStatuses = new Set([
  "APPROVED_FOR_EXECUTION",
  "EXECUTING",
  "CLOSED",
  "MANUAL_HANDOFF",
]);
const isCaseParty = computed(() => casePartyRoles.has(actor.role));

function isAwaitingHumanReview(dispute) {
  if (!dispute || outcomeStatuses.has(dispute.case_status)) return false;
  return (
    humanReviewStatuses.has(dispute.case_status) ||
    dispute.current_room === "REVIEW"
  );
}

const canInitiateDispute = computed(() =>
  isCaseParty.value,
);
const canImportExternal = computed(() =>
  isCaseParty.value,
);
const externalImportStreams = computed(() =>
  activeAgentStreams({
    caseId: "EXTERNAL_IMPORT",
    roomType: "OVERVIEW",
    actorId: actor.id,
    actorRole: actor.role,
  }),
);
const simulatedSourceSystems = new Set([
  "TEMPLATE_SIMULATED_OMS",
  "LLM_SIMULATED_OMS",
]);

// 业务位置：【前端案件页面】isReviewerDeletableCase：判断 当前阶段业务数据 是否满足当前流程分支的进入条件。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function isReviewerDeletableCase(dispute) {
  if (!dispute) return false;
  if (dispute.source_type === "INTAKE_CREATED") return true;
  return (
    dispute.source_type === "EXTERNAL_IMPORT" &&
    simulatedSourceSystems.has(dispute.source_system)
  );
}

const canDeleteSelectedCase = computed(() =>
  actor.role === "PLATFORM_REVIEWER" &&
  isReviewerDeletableCase(selectedCase.value),
);

const journey = [
  { key: "INTAKE", room: "INTAKE", label: "案情接待", agent: "接待官整理事实" },
  { key: "EVIDENCE", room: "EVIDENCE", label: "证据核验", agent: "证据书记官复核" },
  { key: "HEARING", room: "HEARING", label: "智能庭审", agent: "多角色分阶段审理" },
  { key: "DRAFT", room: "DRAFT", label: "裁决草案", agent: "法官生成草案" },
  { key: "REVIEW", room: "REVIEW", label: "人工终审", agent: "审核员最终确认" },
  { key: "OUTCOME", room: "OUTCOME", label: "执行结果", agent: "执行专员处理" },
];
const riskLabels = {
  CRITICAL: "极高风险",
  HIGH: "高风险",
  MEDIUM: "中风险",
  LOW: "低风险",
};
const pendingActionLabels = {
  COMPLETE_INTAKE: "完成受理确认",
  SUBMIT_EVIDENCE: "提交证据",
  ENTER_HEARING: "进入小法庭",
  PARTICIPATE_HEARING: "参与庭审",
  REVIEW_SETTLEMENT: "确认一致方案",
  AWAIT_REVIEW: "等待平台终审",
  TRACK_EXECUTION: "跟踪执行",
  VIEW_OUTCOME: "查看处理结果",
  CONTINUE_CASE: "继续处理",
};

const currentRoomIndex = computed(() => {
  const status = selectedCase.value?.case_status;
  const room = selectedCase.value?.current_room;
  if (outcomeStatuses.has(status)) {
    return journey.length - 1;
  }
  if (room === "REVIEW") return 4;
  if (
    room === "DRAFT" ||
    [
      "DRAFT_READY",
      "DELIBERATION_RUNNING",
      "REVIEW_PENDING",
      "WAITING_HUMAN_REVIEW",
      "REMEDY_PLANNED",
    ].includes(status)
  ) {
    return 3;
  }
  if (room === "INTAKE") return 0;
  if (room === "EVIDENCE") return 1;
  if (room === "HEARING") return 2;
  if (room === "OUTCOME") return 5;
  return 0;
});
const currentStage = computed(() => journey[currentRoomIndex.value] || journey[0]);
const journeyProgress = computed(() => `${currentRoomIndex.value + 1} / ${journey.length}`);
const partyAwaitingHumanReview = computed(() =>
  isCaseParty.value && isAwaitingHumanReview(selectedCase.value),
);
const currentRoomActionDisabled = computed(() =>
  partyAwaitingHumanReview.value || Boolean(openingStage.value),
);
const currentRoomActionLabel = computed(() => {
  if (partyAwaitingHumanReview.value) return "等待人工终审";
  if (currentRoomIndex.value === 5) return "查看最终结果";
  if (currentRoomIndex.value === 4) {
    return actor.role === "PLATFORM_REVIEWER" ? "进入终审室" : "查看裁决草案";
  }
  if (currentRoomIndex.value === 3) return "进入裁决草案室";
  return "进入当前房间";
});

// 业务位置：【前端案件页面】stageState：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function stageState(index) {
  if (isCaseParty.value && journey[index]?.room === "REVIEW") return "locked";
  if (index < currentRoomIndex.value) return "completed";
  if (index === currentRoomIndex.value) return "current";
  return "locked";
}

function stageStatus(index) {
  if (
    isCaseParty.value &&
    journey[index]?.room === "REVIEW" &&
    currentRoomIndex.value >= index
  ) {
    return "权限锁定";
  }
  const state = stageState(index);
  if (state === "completed") return "历史 · 只读";
  if (state === "current") return "当前房间";
  if (index === currentRoomIndex.value + 1) return "下一站";
  return "待解锁";
}

const stageRouteNames = {
  INTAKE: "intake-room",
  EVIDENCE: "evidence-room",
  HEARING: "hearing-court",
  DRAFT: "adjudication-draft-room",
  OUTCOME: "dispute-outcome",
};

function isPartyRestrictedReviewStage(stage, index) {
  return (
    isCaseParty.value &&
    stage?.room === "REVIEW" &&
    currentRoomIndex.value >= index
  );
}

function stageEntryDisabled(stage, index) {
  if (isPartyRestrictedReviewStage(stage, index)) return Boolean(openingStage.value);
  if (stageState(index) === "locked" || openingStage.value) return true;
  return false;
}

function showReviewPermission() {
  stageNavigationError.value = "";
  reviewPermissionOpen.value = true;
}

function closeReviewPermission() {
  reviewPermissionOpen.value = false;
}

async function redirectToRequiredIntake(dispute, targetRoom) {
  if (
    String(targetRoom || "").toUpperCase() !== "EVIDENCE" ||
    !isCaseParty.value
  ) {
    return false;
  }
  const intakeStatus = await disputeApi.intakeStatus(actor, dispute.id);
  const currentActorCompleted = Boolean(
    intakeStatus?.current_actor_completed ?? intakeStatus?.currentActorCompleted,
  );
  const canEnterEvidence = Boolean(
    intakeStatus?.can_enter_evidence ?? intakeStatus?.canEnterEvidence,
  );
  if (currentActorCompleted && canEnterEvidence) return false;
  await router.push(`/disputes/${dispute.id}/intake`);
  return true;
}

async function enterStage(stage, index) {
  const dispute = selectedCase.value;
  if (!dispute) return;
  if (isPartyRestrictedReviewStage(stage, index)) {
    showReviewPermission();
    return;
  }
  if (stageEntryDisabled(stage, index)) return;
  const historical = index < currentRoomIndex.value;
  stageNavigationError.value = "";
  openingStage.value = stage.key;
  try {
    if (stage.room === "REVIEW") {
      if (actor.role !== "PLATFORM_REVIEWER") {
        await router.push({
          path: `/disputes/${dispute.id}/draft`,
          query: historical ? { view: "history" } : {},
        });
        return;
      }
      const result = await disputeApi.outcome(actor, dispute.id);
      const reviewTaskId = result?.review_task_id || result?.reviewTaskId;
      if (!reviewTaskId) {
        stageNavigationError.value = "未找到该案件的终审记录。";
        return;
      }
      await router.push({
        path: `/reviews/${encodeURIComponent(reviewTaskId)}`,
        query: historical ? { view: "history" } : {},
      });
      return;
    }
    if (await redirectToRequiredIntake(dispute, stage.room)) return;
    const name = stageRouteNames[stage.room];
    if (!name) return;
    await router.push({
      name,
      params: { caseId: dispute.id },
      query: historical ? { view: "history" } : {},
    });
  } catch (failure) {
    stageNavigationError.value = failure?.message || "暂时无法打开该阶段记录。";
  } finally {
    openingStage.value = "";
  }
}

// 业务位置：【前端案件页面】enterCurrentRoom：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function enterCurrentRoom() {
  const dispute = selectedCase.value;
  if (!dispute) return;
  if (partyAwaitingHumanReview.value) return;
  if (outcomeStatuses.has(dispute.case_status)) {
    router.push(`/disputes/${dispute.id}/outcome`);
    return;
  }
  if (dispute.current_room === "REVIEW" && actor.role === "PLATFORM_REVIEWER") {
    router.push("/reviews");
    return;
  }
  if (currentRoomIndex.value >= 3) {
    router.push(`/disputes/${dispute.id}/draft`);
    return;
  }
  if (
    dispute.current_room === "EVIDENCE" &&
    isCaseParty.value
  ) {
    stageNavigationError.value = "";
    openingStage.value = "CURRENT";
    try {
      if (await redirectToRequiredIntake(dispute, dispute.current_room)) return;
    } catch (failure) {
      stageNavigationError.value = failure?.message || "暂时无法确认当前接待进度。";
      return;
    } finally {
      openingStage.value = "";
    }
  }
  const routes = { INTAKE: "intake", EVIDENCE: "evidence", HEARING: "hearing" };
  await router.push(`/disputes/${dispute.id}/${routes[dispute.current_room] || "intake"}`);
}

// 业务位置：【前端案件页面】sourceLabel：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function sourceLabel(source) {
  return source === "EXTERNAL_IMPORT" ? "外部导入" : "接待官创建";
}

// 业务位置：【前端案件页面】roomLabel：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function roomLabel(dispute) {
  if (!dispute) return "待分配房间";
  const status = dispute.case_status;
  if (outcomeStatuses.has(status)) {
    return "执行结果";
  }
  if (isCaseParty.value && isAwaitingHumanReview(dispute)) return "等待人工终审";
  if (dispute.current_room === "REVIEW") return "平台人工终审";
  if (["DRAFT_READY", "REVIEW_PENDING", "WAITING_HUMAN_REVIEW", "REMEDY_PLANNED"].includes(status)) {
    return "裁决草案";
  }
  return journey.find((stage) => stage.room === dispute.current_room)?.label || dispute.current_room || "待分配房间";
}

// 业务位置：【前端案件页面】riskLabel：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function riskLabel(risk) {
  return riskLabels[risk] || risk || "未评估";
}

// 业务位置：【前端案件页面】pendingActionLabel：围绕 履约执行动作和工具意图 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function pendingActionLabel(action, dispute = selectedCase.value) {
  if (action === "AWAIT_REVIEW") {
    if (isCaseParty.value && isAwaitingHumanReview(dispute)) return "等待人工终审";
    return dispute?.current_room === "REVIEW" ? "平台终审进行中" : "查看裁决草案";
  }
  return pendingActionLabels[action] || action || "等待下一步";
}

// 业务位置：【前端案件页面】openIntake：切换与 案件受理信息和接待结论 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function openIntake() {
  if (!canInitiateDispute.value) return;
  intakeForm.value.userId = actor.role === "MERCHANT" ? "user-local" : actor.id;
  intakeForm.value.merchantId = actor.role === "MERCHANT" ? actor.id : "merchant-local";
  intakeForm.value.requestedResolution = "VERIFY_OR_EXPLAIN_ONLY";
  intakeForm.value.requestedAmount = "";
  intakeForm.value.requestedItems = "";
  intakeForm.value.description = "";
  createError.value = "";
  intakeOpen.value = true;
}

// 业务位置：【前端案件页面】simulatedCounterpartyId：围绕 当事人主张、角色和对方态度 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function simulatedCounterpartyId(role) {
  return role === "MERCHANT" ? "user-local" : "merchant-local";
}

// 业务位置：【前端案件页面】normalizeImportedCase：将 当前阶段业务数据 转换为稳定的接口、提示词或页面表达，避免直接暴露内部实现字段。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function normalizeImportedCase(item) {
  const id = item.id || item.case_id;
  return {
    id,
    order_id:
      item.order_id ||
      item.order_reference ||
      item.orderId ||
      item.orderReference ||
      "外部订单待同步",
    source_type: item.source_type || item.sourceType || "EXTERNAL_IMPORT",
    source_system: item.source_system || item.sourceSystem || null,
    external_case_reference:
      item.external_case_reference || item.externalCaseReference || null,
    dispute_type: item.dispute_type || item.disputeType || "FULFILLMENT_CONFLICT",
    case_status: item.case_status || item.caseStatus || "INTAKE_PENDING",
    current_room: item.current_room || item.currentRoom || "INTAKE",
    deadline_at:
      item.deadline_at ||
      item.current_deadline_at ||
      item.deadlineAt ||
      item.currentDeadlineAt ||
      null,
    risk_level: item.risk_level || item.riskLevel || "MEDIUM",
    pending_action: item.pending_action || item.pendingAction || "COMPLETE_INTAKE",
    title: item.title || "外部导入争议",
    initiator_role: item.initiator_role || item.initiatorRole,
  };
}

// 业务位置：【前端案件页面】simulateExternalImport：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function simulateExternalImport() {
  if (!canImportExternal.value || importingExternal.value) return;
  const initiatorRole = actor.role === "MERCHANT" ? "MERCHANT" : "USER";
  const command = {
    count: 1,
    scenario: "手表售后争议",
    risk_level_hint: "MEDIUM",
    initiator_role_hint: initiatorRole,
    current_actor_id: ["USER", "MERCHANT"].includes(actor.role)
      ? actor.id
      : "user-local",
    counterparty_actor_id: simulatedCounterpartyId(initiatorRole),
  };
  importingExternal.value = true;
  importError.value = "";
  importStreamError.value = "";
  try {
    const result = props.simulateExternalImportAction
      ? await props.simulateExternalImportAction(command)
      : await disputeApi.simulateExternalImport(actor, command);
    const descriptor = extractAgentRunDescriptor(result);
    if (descriptor) {
      await consumeAgentRun({
        actor: { id: actor.id, role: actor.role },
        caseId: "EXTERNAL_IMPORT",
        roomType: "OVERVIEW",
        descriptor,
        agentLabel: "外部案件导入助手",
        senderRole: "SYSTEM",
        onFinal: applyExternalImportResult,
        onError: (failure) => {
          importStreamError.value = failure.message;
        },
      });
    } else {
      await applyExternalImportResult(result);
    }
  } catch (failure) {
    importError.value = failure.message;
  } finally {
    importingExternal.value = false;
  }
}

// 业务位置：【前端案件页面】applyExternalImportResult：围绕 阶段处理结果或草案 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function applyExternalImportResult(result) {
  const imported = (result?.items || []).map(normalizeImportedCase);
  if (imported.length) {
    const existing = localCases.value.length ? localCases.value : disputeStore.list.data;
    const importedIds = new Set(imported.map((item) => item.id));
    localCases.value = [
      ...imported,
      ...existing.filter((item) => !importedIds.has(item.id)),
    ];
    selectedId.value = imported[0].id;
  } else {
    await loadDisputes(actor);
    localCases.value = [...disputeStore.list.data];
    selectedId.value = localCases.value[0]?.id || null;
  }
}

// 业务位置：【前端案件页面】dismissImportStreamError：切换与 Agent 流事件 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function dismissImportStreamError() {
  const previous = importStreamError.value;
  importStreamError.value = "";
  if (importError.value === previous) importError.value = "";
}

// 业务位置：【前端案件页面】openDeleteCaseConfirmation：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function openDeleteCaseConfirmation() {
  if (!canDeleteSelectedCase.value) return;
  deleteCandidate.value = selectedCase.value;
  deleteError.value = "";
}

// 业务位置：【前端案件页面】closeDeleteCaseConfirmation：切换与 当前阶段业务数据 对应的页面或房间状态，使用户操作匹配当前案件阶段。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function closeDeleteCaseConfirmation() {
  if (deletingCase.value) return;
  deleteCandidate.value = null;
  deleteError.value = "";
}

// 业务位置：【前端案件页面】confirmDeleteCase：执行 当前阶段业务数据 对应的业务动作，并将结果交给 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function confirmDeleteCase() {
  if (deletingCase.value) return;
  const candidate = deleteCandidate.value;
  if (
    actor.role !== "PLATFORM_REVIEWER" ||
    !isReviewerDeletableCase(candidate)
  ) {
    deleteError.value = "当前角色无权删除该案例";
    return;
  }

  deletingCase.value = true;
  deleteError.value = "";
  try {
    if (props.deleteSimulatedCaseAction) {
      await props.deleteSimulatedCaseAction(candidate.id);
    } else {
      await disputeApi.deleteSimulatedCase(actor, candidate.id);
    }

    const snapshot = [...cases.value];
    const deletedIndex = snapshot.findIndex((item) => item.id === candidate.id);
    const remaining = snapshot.filter((item) => item.id !== candidate.id);
    const nextSelection =
      remaining[deletedIndex] ||
      remaining[deletedIndex - 1] ||
      remaining[0] ||
      null;

    localCases.value = remaining;
    disputeStore.list.data = disputeStore.list.data.filter(
      (item) => item.id !== candidate.id,
    );
    selectedId.value = nextSelection?.id || null;
    deleteCandidate.value = null;
    await loadDisputes(actor);
    if (!props.initialCases.length) {
      reconcileCases(disputeStore.list.data);
    }
  } catch (failure) {
    deleteError.value = failure?.message || "删除失败，请稍后重试";
  } finally {
    deletingCase.value = false;
  }
}

// 业务位置：【前端案件页面】createDispute：把 路由参数、API 数据和状态仓库 组装为本块需要的 当前阶段业务数据，供 用户可操作的案件视图 使用。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function createDispute() {
  if (!canInitiateDispute.value) return;
  creating.value = true;
  createError.value = "";
  const requestedAmount = Number.parseFloat(intakeForm.value.requestedAmount);
  const command = {
    initiator_role: actor.role,
    order_reference: intakeForm.value.orderReference || null,
    after_sales_reference: intakeForm.value.afterSalesReference || null,
    logistics_reference: intakeForm.value.logisticsReference || null,
    user_id: intakeForm.value.userId,
    merchant_id: intakeForm.value.merchantId,
    description: intakeForm.value.description,
    claim_resolution_seed: {
      initiator_role: actor.role,
      requested_resolution: intakeForm.value.requestedResolution,
      requested_amount: Number.isFinite(requestedAmount) ? requestedAmount : null,
      requested_items: intakeForm.value.requestedItems || null,
      request_reason: intakeForm.value.description,
    },
    attachment_ids: [],
    channel: "WEB",
  };
  try {
    const created = props.createAction
      ? await props.createAction(command)
      : await disputeApi.create(actor, command);
    await router.push(`/disputes/${created.id}/intake`);
  } catch (failure) {
    createError.value = failure.message;
  } finally {
    creating.value = false;
  }
}

onMounted(async () => {
  if (localCases.value.length) return;
  await loadDisputes(actor);
  selectedId.value ||= disputeStore.list.data[0]?.id || null;
});
onBeforeUnmount(() => {
  clearAgentStreams({ caseId: "EXTERNAL_IMPORT", roomType: "OVERVIEW" });
});
</script>

<template>
  <section class="overview-page">
    <header class="overview-page__intro">
      <div>
        <span>AI NATIVE DISPUTE PARK</span>
        <h1>今天要去哪个争议房间？</h1>
        <p class="overview-page__lead" data-overview-lead>
          <span class="overview-page__context">
            争议办理总览 <i aria-hidden="true">✦</i>
          </span>
          这里只有已经进入争端流程的订单。选中案件，右侧路线会告诉你下一步。
        </p>
      </div>
      <div v-if="canDeleteSelectedCase" class="overview-page__actions">
        <button
          class="overview-page__delete"
          type="button"
          data-delete-case
          data-delete-simulated-case
          @click="openDeleteCaseConfirmation"
        >
          <span aria-hidden="true">×</span>
          删除案件
        </button>
      </div>
    </header>
    <div
      v-if="externalImportStreams.length"
      class="overview-page__import-stream"
      data-external-import-stream
    >
      <AgentStreamingMessage
        v-for="run in externalImportStreams"
        :key="run.runId"
        :run="run"
        :label="run.agentLabel"
      />
    </div>
    <p v-if="importError" class="overview-page__error" role="alert">{{ importError }}</p>

    <AgentStreamErrorDialog
      :message="importStreamError"
      title="外部案件生成失败"
      @dismiss="dismissImportStreamError"
    />

    <section
      v-if="selectedCase"
      class="overview-guide-card"
      data-overview-guide
    >
      <DigitalHuman
        state="HANDOFF"
        name="小途"
        role="路线引导员"
        :message="`当前位于${currentStage.label}。${pendingActionLabel(selectedCase?.pending_action)}`"
      />
    </section>

    <div class="overview-layout" :class="{ 'overview-layout--empty': !cases.length }">
      <div
        v-if="canImportExternal || canInitiateDispute"
        class="overview-case-actions"
        aria-label="争议订单操作"
      >
        <button
          v-if="canImportExternal"
          class="overview-page__import"
          type="button"
          data-simulate-external-import
          :disabled="importingExternal"
          @click="simulateExternalImport"
        >
          <span aria-hidden="true">⇢</span>
          {{ importingExternal ? "外部系统导入中" : "导入外部争议" }}
        </button>
        <button
          v-if="canInitiateDispute"
          class="overview-page__start"
          type="button"
          data-start-dispute
          @click="openIntake"
        >
          <span aria-hidden="true">＋</span>
          发起争议审理
        </button>
      </div>

      <aside v-if="cases.length" class="dispute-rail" aria-label="争议订单">
        <div class="dispute-rail__heading">
          <div>
            <span>MY DISPUTES</span>
            <h2>争议订单轨道</h2>
          </div>
          <strong>{{ cases.length }}</strong>
        </div>

        <div class="dispute-rail__scroll" data-dispute-rail-scroll>
          <button
            v-for="item in cases"
            :key="item.id"
            type="button"
            class="dispute-ticket"
            :class="{ 'dispute-ticket--active': item.id === selectedCase?.id }"
            :data-case-id="item.id"
            :data-source-system="item.source_system || undefined"
            @click="selectedId = item.id"
          >
            <span class="dispute-ticket__topline">
              <small>{{ sourceLabel(item.source_type) }}</small>
              <small :data-risk="item.risk_level">{{ riskLabel(item.risk_level) }}</small>
            </span>
            <strong>{{ item.title }}</strong>
            <span class="dispute-ticket__room">
              <i aria-hidden="true" />
              {{ roomLabel(item) }}
            </span>
            <small>{{ pendingActionLabel(item.pending_action, item) }}</small>
          </button>
        </div>
      </aside>

      <main v-if="cases.length" class="hearing-adventure" data-hearing-adventure>
        <header
          class="hearing-adventure__header"
          data-case-journey-dashboard
          aria-label="当前案件态势"
        >
          <div class="hearing-adventure__identity">
            <span aria-hidden="true">⌁</span>
            <div>
              <small>CASE JOURNEY</small>
              <h2 :title="selectedCase?.title">{{ selectedCase?.title }}</h2>
            </div>
          </div>
          <div class="hearing-adventure__header-status">
            <strong>本案进度 {{ journeyProgress }}</strong>
            <PhaseCountdown
              v-if="selectedCase?.deadline_at"
              :deadline-at="selectedCase.deadline_at"
              :server-now="serverNow"
              label="当前房间剩余"
            />
          </div>
        </header>

        <section class="hearing-adventure__viewport">
          <div class="hearing-adventure__map">
            <span class="hearing-adventure__terrain hearing-adventure__terrain--mint" aria-hidden="true" />
            <span class="hearing-adventure__terrain hearing-adventure__terrain--yellow" aria-hidden="true" />
            <span class="hearing-adventure__terrain hearing-adventure__terrain--blue" aria-hidden="true" />
            <svg
              class="adventure-path__route"
              viewBox="0 0 1000 330"
              preserveAspectRatio="none"
              aria-hidden="true"
            >
              <path d="M 95 240 C 170 235, 165 100, 270 105 S 365 245, 445 220 S 520 180, 600 185 S 690 45, 755 90 S 865 225, 925 190" />
            </svg>

            <button
              type="button"
              class="map-room-entry"
              data-enter-current-room
              :data-waiting-human-review="partyAwaitingHumanReview ? 'true' : undefined"
              :disabled="currentRoomActionDisabled"
              @click="enterCurrentRoom"
            >
              <span aria-hidden="true">→</span>
              <span>
                <strong>{{ currentRoomActionLabel }}</strong>
                <small>{{ currentStage.label }} · {{ pendingActionLabel(selectedCase?.pending_action) }}</small>
              </span>
            </button>

            <ol class="adventure-path" data-adventure-path>
              <li
                v-for="(stage, index) in journey"
                :key="stage.key"
                :data-stage-state="stageState(index)"
                :data-stage-room="stage.room"
              >
                <span class="adventure-path__orb">{{ index + 1 }}</span>
                <strong>{{ stage.label }}</strong>
                <small>{{ stageStatus(index) }}</small>
                <button
                  type="button"
                  class="adventure-path__hit"
                  :data-stage-entry="stage.room"
                  :disabled="stageEntryDisabled(stage, index)"
                  :data-permission-locked="isPartyRestrictedReviewStage(stage, index) ? 'true' : undefined"
                  :aria-label="`${stage.label}：${stageStatus(index)}`"
                  @click="enterStage(stage, index)"
                />
              </li>
            </ol>

            <p v-if="stageNavigationError" class="adventure-path__error" role="alert">
              {{ stageNavigationError }}
            </p>

            <div class="hearing-adventure__finish">
              <span aria-hidden="true">⚑</span>
              <strong>终点由人类确认</strong>
            </div>
          </div>
        </section>
      </main>

      <div v-else class="overview-empty">
        <span aria-hidden="true">🎟</span>
        <h2>还没有争议订单</h2>
        <p>外部导入或接待官创建的争议会出现在这里，普通订单不会进入本页。</p>
      </div>
    </div>

    <div
      v-if="reviewPermissionOpen"
      class="review-permission-dialog"
      data-review-permission-dialog
      role="presentation"
      @click.self="closeReviewPermission"
    >
      <section
        class="review-permission-dialog__card"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="review-permission-title"
        aria-describedby="review-permission-description"
        @keydown.esc.stop="closeReviewPermission"
      >
        <button
          type="button"
          class="review-permission-dialog__close"
          aria-label="关闭无权限提示"
          title="关闭"
          @click="closeReviewPermission"
        >
          ×
        </button>
        <span>ACCESS RESTRICTED</span>
        <h2 id="review-permission-title">抱歉您没有权限</h2>
        <p id="review-permission-description">
          人工终审室仅向平台审核员开放，请等待终审完成后查看执行结果。
        </p>
        <button
          type="button"
          class="review-permission-dialog__acknowledge"
          data-close-review-permission
          autofocus
          @click="closeReviewPermission"
        >
          我知道了
        </button>
      </section>
    </div>

    <div v-if="intakeOpen" class="intake-launcher" role="dialog" aria-modal="true">
      <form class="intake-launcher__card" @submit.prevent="createDispute">
        <header>
          <div>
            <span>NEW DISPUTE TICKET</span>
            <h2>请争议接待官开一张新案卡</h2>
            <p>先填写订单引用、初始诉求与一段自然语言陈述，进入接待室后由接待官继续追问。</p>
          </div>
          <button type="button" aria-label="关闭发起争议窗口" @click="intakeOpen = false">×</button>
        </header>
        <div class="intake-launcher__fields">
          <label>
            订单引用
            <input v-model="intakeForm.orderReference" data-intake-order placeholder="例如 ORDER-20260703" />
          </label>
          <label>
            售后引用
            <input v-model="intakeForm.afterSalesReference" placeholder="可选" />
          </label>
          <label>
            物流引用
            <input v-model="intakeForm.logisticsReference" placeholder="可选" />
          </label>
          <label>
            用户 ID
            <input v-model="intakeForm.userId" data-intake-user required />
          </label>
          <label>
            商家 ID
            <input v-model="intakeForm.merchantId" data-intake-merchant required />
          </label>
          <section class="intake-launcher__claim" data-claim-resolution-section>
            <div class="intake-launcher__claim-copy">
              <span>初始诉求</span>
              <p>这里记录的是发起方主张，不代表系统已执行退款、补发或赔付。</p>
            </div>
            <label>
              初始诉求类型
              <select v-model="intakeForm.requestedResolution" data-claim-resolution-type required>
                <option
                  v-for="option in claimResolutionOptions"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label>
              诉求金额
              <input
                v-model="intakeForm.requestedAmount"
                data-claim-requested-amount
                inputmode="decimal"
                placeholder="可选，例如 299"
              />
            </label>
            <label>
              涉及商品 / 数量
              <input
                v-model="intakeForm.requestedItems"
                data-claim-requested-items
                placeholder="可选，例如 儿童手表 1 件"
              />
            </label>
          </section>
          <label class="intake-launcher__story">
            发生了什么
            <textarea
              v-model="intakeForm.description"
              data-intake-description
              rows="4"
              required
              placeholder="描述争议经过、对方态度和你的期望处理结果；这段会作为原始陈述展示，不会被改写。"
            />
          </label>
        </div>
        <p v-if="createError" class="intake-launcher__error" role="alert">{{ createError }}</p>
        <footer>
          <span>创建后进入争议接待室，AI 判断仍需由当事人确认。</span>
          <button type="submit" data-submit-dispute :disabled="creating">
            {{ creating ? "接待官正在建档…" : "进入接待室" }}
          </button>
        </footer>
      </form>
    </div>

    <div
      v-if="deleteCandidate"
      class="delete-case-modal"
      data-delete-case-modal
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-case-title"
      @click.self="closeDeleteCaseConfirmation"
    >
      <section class="delete-case-modal__card">
        <span class="delete-case-modal__eyebrow">REVIEWER CASE CLEANUP</span>
        <h2 id="delete-case-title">确认删除这个案件？</h2>
        <p>
          将永久删除“{{ deleteCandidate.title }}”（{{ deleteCandidate.id }}）及其案例数据，
          <strong>删除后不可恢复</strong>。
        </p>
        <p v-if="deleteError" class="delete-case-modal__error" role="alert">
          {{ deleteError }}
        </p>
        <footer>
          <button
            type="button"
            data-cancel-delete-case
            :disabled="deletingCase"
            @click="closeDeleteCaseConfirmation"
          >
            取消
          </button>
          <button
            type="button"
            data-confirm-delete-case
            :disabled="deletingCase"
            @click="confirmDeleteCase"
          >
            {{ deletingCase ? "正在删除…" : "确认永久删除" }}
          </button>
        </footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.overview-page {
  display: grid;
  gap: 24px;
  min-width: 0;
}
.overview-page__intro {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-end;
}
.overview-page__intro > div { min-width: 0; }
.overview-page__import-stream {
  display: grid;
  width: min(760px, 100%);
  margin: -10px 0 14px;
}
.overview-page__import-stream :deep(.agent-streaming-message) {
  width: 100%;
  max-width: 100%;
}
.overview-page__actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: nowrap;
  justify-content: flex-end;
  gap: 10px;
}
.overview-page__intro > div > span,
.dispute-rail__heading span,
.hearing-adventure__header > div > span {
  color: #7185a8;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .18em;
}
.overview-page__intro h1 {
  margin: 7px 0 8px;
  min-width: 0;
  color: #263754;
  font-size: clamp(32px, 5vw, 56px);
  line-height: 1.05;
  overflow-wrap: anywhere;
}
.overview-page__lead {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
  align-items: center;
  margin: 0;
  min-width: 0;
  color: #738097;
  overflow-wrap: anywhere;
}
.overview-page__context {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  color: #586b85;
  font-size: 12px;
  font-weight: 900;
  line-height: 1;
}
.overview-page__context i {
  color: #ff9a76;
  font-size: 9px;
  font-style: normal;
}
.overview-page__start,
.overview-page__import,
.overview-page__delete,
.hearing-adventure__next > button {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  color: #fff;
  background: linear-gradient(135deg, #ff8c72, #ff6f72);
  border: 0;
  border-radius: 17px;
  box-shadow: 0 14px 28px #ec75633d;
  cursor: pointer;
  font-weight: 800;
}
.overview-page__start span {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  color: #ff746c;
  background: #fff;
  border-radius: 50%;
}
.overview-page__import {
  color: #38617f;
  background: linear-gradient(135deg, #dcf3ff, #fff0ce);
  border: 1px solid #cce5f8;
  box-shadow: 0 14px 28px #8db6d22b;
}
.overview-page__import span {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  color: #fff;
  background: #73b9ef;
  border-radius: 50%;
}
.overview-page__delete {
  color: #9a4652;
  background: linear-gradient(135deg, #fff2f1, #ffe7df);
  border: 1px solid #f1c8c5;
  box-shadow: 0 14px 28px #bd686326;
}
.overview-page__delete span {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  color: #fff;
  background: #d76c71;
  border-radius: 50%;
  font-size: 18px;
  line-height: 1;
}
.overview-page__import:disabled {
  cursor: progress;
  opacity: .72;
}
.overview-page__error {
  margin: -8px 0 0;
  color: #a84451;
  font-size: 13px;
}
.overview-layout {
  display: grid;
  grid-template-columns: minmax(300px, 350px) minmax(0, 1fr);
  height: clamp(720px, calc(100dvh - 170px), 780px);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #f7fbff;
  border: 1px solid #dde8f5;
  border-radius: 32px;
  box-shadow: 0 24px 70px #506c9a1a;
}
.dispute-rail {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 8px;
  padding: 20px;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: linear-gradient(180deg, #f0f8ff, #fff8f2);
  border-right: 1px solid #dde8f5;
}
.dispute-rail__scroll {
  min-width: 0;
  min-height: 0;
  padding: 2px 5px 6px 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scroll-padding: 12px;
  scroll-snap-type: y proximity;
}
.dispute-rail__scroll::-webkit-scrollbar { width: 8px; }
.dispute-rail__scroll::-webkit-scrollbar-thumb {
  background: linear-gradient(180deg, #acd7f6, #ffd3bb);
  border-radius: 999px;
}
.dispute-rail__scroll::-webkit-scrollbar-track { background: transparent; }
.dispute-rail__heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
  min-width: 0;
}
.dispute-rail__heading > div { min-width: 0; }
.dispute-rail__heading h2 { margin: 4px 0 0; color: #34435e; font-size: 20px; }
.dispute-rail__heading strong {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #387cad;
  background: #dff1ff;
  border-radius: 13px;
}
.dispute-ticket {
  display: grid;
  width: 100%;
  min-width: 0;
  gap: 7px;
  margin-top: 10px;
  padding: 15px;
  text-align: left;
  color: #39465d;
  background: #ffffffc9;
  border: 1px solid transparent;
  border-radius: 21px;
  cursor: pointer;
  scroll-snap-align: start;
  transition:
    border-color .18s ease,
    box-shadow .18s ease,
    transform .18s ease;
}
.dispute-ticket:first-child { margin-top: 0; }
.dispute-ticket--active {
  background: #fff;
  border-color: #7fc5f4;
  box-shadow: 0 13px 28px #4d86b51c;
  transform: translateX(3px);
}
.dispute-ticket__topline { display: flex; justify-content: space-between; gap: 8px; }
.dispute-ticket__topline small:first-child {
  padding: 4px 8px;
  color: #47749d;
  background: #e5f3ff;
  border-radius: 999px;
}
.dispute-ticket__topline [data-risk="HIGH"],
.dispute-ticket__topline [data-risk="CRITICAL"] { color: #b84f52; }
.dispute-ticket > strong {
  display: -webkit-box;
  margin-top: 4px;
  overflow: hidden;
  font-size: 16px;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.dispute-ticket > span,
.dispute-ticket > small { color: #7a879b; }
.dispute-ticket > small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dispute-ticket__room { display: flex; align-items: center; gap: 7px; font-weight: 800; }
.dispute-ticket__room i { width: 8px; height: 8px; background: #61c997; border-radius: 50%; }
.hearing-adventure {
  --overview-journey-gap: 12px;
  position: relative;
  display: grid;
  grid-template-rows: auto auto minmax(112px, 1fr) 218px;
  gap: var(--overview-journey-gap);
  padding: 24px;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 86% 12%, #ffe5b4 0 5%, transparent 5.4%),
    linear-gradient(180deg, #eef8ff, #fffaf2 74%);
}
.hearing-adventure__sky { position: absolute; inset: 0; pointer-events: none; }
.hearing-adventure__sky span { position: absolute; color: #a98bec; opacity: .55; }
.hearing-adventure__sky span:nth-child(1) { top: 38px; right: 28%; }
.hearing-adventure__sky span:nth-child(2) { top: 90px; right: 8%; font-size: 40px; color: white; }
.hearing-adventure__sky span:nth-child(3) { top: 170px; left: 10%; }
.hearing-adventure__header {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-start;
  min-width: 0;
}
.hearing-adventure__header > div { min-width: 0; }
.hearing-adventure__header h2,
.hearing-adventure__header p {
  display: -webkit-box;
  min-width: 0;
  overflow: hidden;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.hearing-adventure__header h2 {
  margin: 5px 0;
  color: #2c3e5b;
  font-size: 28px;
  line-height: 1.18;
}
.hearing-adventure__header p {
  margin: 0;
  color: #748298;
  line-height: 1.45;
}
.hearing-adventure__case-board {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 12px;
  min-width: 0;
  min-height: 0;
}
.hearing-adventure__case-board article {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 5px;
  align-content: center;
  min-width: 0;
  min-height: 0;
  padding: 12px 14px;
  overflow: hidden;
  background: #ffffffc9;
  border: 1px solid #dce8f3;
  border-radius: 22px;
  box-shadow: 0 12px 32px #58759a14;
}
.hearing-adventure__case-board span {
  color: #8a98ad;
  font-size: 9px;
  font-weight: 900;
  letter-spacing: .14em;
}
.hearing-adventure__case-board strong {
  align-self: center;
  min-width: 0;
  overflow: hidden;
  color: #2d3e5c;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hearing-adventure__case-board small {
  min-width: 0;
  overflow: hidden;
  color: #73839a;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.adventure-path {
  position: relative;
  z-index: 1;
  display: grid;
  width: 100%;
  grid-template-columns: repeat(5, minmax(128px, 1fr));
  align-items: center;
  gap: 4px;
  align-self: stretch;
  margin: 0;
  min-width: 0;
  min-height: 112px;
  padding: 14px 0 8px;
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-inline: contain;
  scrollbar-width: thin;
  list-style: none;
}
.adventure-path::-webkit-scrollbar { height: 7px; }
.adventure-path::-webkit-scrollbar-thumb {
  background: #b9d7ec;
  border-radius: 999px;
}
.adventure-path li {
  position: relative;
  display: grid;
  min-width: 0;
  justify-items: center;
  gap: 10px;
  text-align: center;
}
.adventure-path__connector {
  position: absolute;
  top: 34px;
  left: 50%;
  width: 100%;
  height: 7px;
  background: #dbe6ef;
}
.adventure-path li:last-child .adventure-path__connector { display: none; }
.adventure-path li[data-stage-state="completed"] .adventure-path__connector { background: #7fd6ad; }
.adventure-path__node {
  position: relative;
  z-index: 1;
  display: grid;
  width: 68px;
  height: 68px;
  place-items: center;
  color: #7c8ca3;
  background: #eef2f6;
  border: 6px solid #fff;
  border-radius: 25px;
  box-shadow: 0 12px 24px #536d9220;
  font-size: 24px;
}
.adventure-path li[data-stage-state="completed"] .adventure-path__node {
  color: #2b8d62;
  background: #c8f1dd;
}
.adventure-path li[data-stage-state="current"] .adventure-path__node {
  color: #fff;
  background: linear-gradient(135deg, #73c4ff, #9b8cf2);
  transform: translateY(-9px) rotate(-3deg);
}
.adventure-path li div { display: grid; gap: 4px; }
.adventure-path small { color: #98a3b4; font-size: 9px; letter-spacing: .08em; }
.adventure-path strong { color: #3c4b63; }
.adventure-path li div span { color: #8591a3; font-size: 11px; }
.hearing-adventure__next {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--overview-journey-gap);
  min-width: 0;
  min-height: 0;
}
.hearing-adventure__next > button { min-width: 0; }
.hearing-adventure__next :deep(.digital-human) { min-width: 0; }
.hearing-adventure__next :deep(.digital-human__copy p) {
  display: -webkit-box;
  overflow: hidden;
  overflow-wrap: anywhere;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.overview-empty {
  display: grid;
  min-height: 460px;
  place-items: center;
  align-content: center;
  text-align: center;
  background: #f7fbff;
  border: 1px dashed #cddbeb;
  border-radius: 30px;
}
.overview-empty > span { font-size: 48px; }
.overview-empty h2 { margin: 12px 0 4px; }
.overview-empty p { max-width: 520px; color: #758298; }
.review-permission-dialog {
  position: fixed;
  inset: 0;
  z-index: 75;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(34, 46, 62, .56);
  backdrop-filter: blur(6px);
}
.review-permission-dialog__card {
  position: relative;
  box-sizing: border-box;
  display: grid;
  width: min(420px, 100%);
  justify-items: center;
  padding: 30px 28px 24px;
  color: #2f4055;
  text-align: center;
  background: #fff;
  border: 1px solid #d9e3ed;
  border-radius: 8px;
  box-shadow: 0 24px 70px rgba(25, 39, 56, .24);
}
.review-permission-dialog__card > span {
  color: #9b6570;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: 0;
}
.review-permission-dialog__card h2 {
  margin: 8px 0 0;
  color: #2b3c53;
  font-size: 22px;
  letter-spacing: 0;
}
.review-permission-dialog__card p {
  margin: 10px 0 0;
  color: #6d7b8d;
  font-size: 13px;
  line-height: 1.7;
}
.review-permission-dialog__close {
  position: absolute;
  top: 7px;
  right: 7px;
  display: grid;
  width: 44px;
  height: 44px;
  padding: 0;
  place-items: center;
  color: #68778a;
  background: transparent;
  border: 0;
  cursor: pointer;
  font-size: 24px;
}
.review-permission-dialog__acknowledge {
  min-width: 118px;
  min-height: 44px;
  margin-top: 22px;
  padding: 0 20px;
  color: #fff;
  background: #52677f;
  border: 1px solid #52677f;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 800;
}
.review-permission-dialog__close:focus-visible,
.review-permission-dialog__acknowledge:focus-visible {
  outline: 3px solid rgba(77, 112, 151, .3);
  outline-offset: 2px;
}
.intake-launcher {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  padding: 20px;
  background: #40547538;
  backdrop-filter: blur(10px);
}
.intake-launcher__card {
  width: min(760px, 100%);
  max-height: calc(100vh - 40px);
  padding: 23px;
  overflow-y: auto;
  background: linear-gradient(145deg, #fff, #f5fbff);
  border: 1px solid #dce8f3;
  border-radius: 30px;
  box-shadow: 0 30px 90px #3e557535;
}
.intake-launcher__card header { display: flex; justify-content: space-between; gap: 20px; }
.intake-launcher__card header span { color: #7186a5; font-size: 10px; font-weight: 900; letter-spacing: .16em; }
.intake-launcher__card header h2 { margin: 6px 0; color: #30415c; }
.intake-launcher__card header p { color: #748197; }
.intake-launcher__card header > button { width: 38px; height: 38px; color: #62728a; background: #edf4fb; border: 0; border-radius: 50%; cursor: pointer; font-size: 22px; }
.intake-launcher__fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; }
.intake-launcher__fields label { display: grid; gap: 6px; color: #607089; font-size: 12px; }
.intake-launcher__fields input,
.intake-launcher__fields select,
.intake-launcher__fields textarea {
  width: 100%; padding: 10px 11px; color: #3d4c63; background: #fff; border: 1px solid #dce5ef; border-radius: 12px;
}
.intake-launcher__fields select {
  appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, #6f83a4 50%), linear-gradient(135deg, #6f83a4 50%, transparent 50%);
  background-position: calc(100% - 18px) 50%, calc(100% - 12px) 50%;
  background-repeat: no-repeat;
  background-size: 6px 6px, 6px 6px;
}
.intake-launcher__claim {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 11px;
  padding: 4px 0 0;
}
.intake-launcher__claim-copy {
  grid-column: 1 / -1;
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  min-height: 22px;
  padding-top: 2px;
}
.intake-launcher__claim-copy span {
  color: #607089;
  font-size: 12px;
  font-weight: 900;
  letter-spacing: .02em;
}
.intake-launcher__claim-copy p {
  margin: 0;
  color: #7b879a;
  font-size: 11px;
  line-height: 1.5;
}
.intake-launcher__story { grid-column: 1 / -1; }
.intake-launcher__card footer { display: flex; justify-content: space-between; gap: 18px; align-items: center; margin-top: 15px; }
.intake-launcher__card footer span { color: #7d899b; font-size: 11px; }
.intake-launcher__card footer button {
  padding: 11px 16px; color: #fff; background: linear-gradient(135deg, #ff8a70, #8a86ee); border: 0; border-radius: 13px; cursor: pointer; font-weight: 800;
}
.intake-launcher__error { color: #a84451; }
.delete-case-modal {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: grid;
  place-items: center;
  padding: 20px;
  background: #4054754d;
  backdrop-filter: blur(10px);
}
.delete-case-modal__card {
  width: min(480px, 100%);
  padding: 26px;
  color: #48566d;
  background: linear-gradient(145deg, #fff, #fff7f3);
  border: 1px solid #f0d2ca;
  border-radius: 27px;
  box-shadow: 0 30px 90px #3e55753d;
}
.delete-case-modal__eyebrow {
  color: #b26b70;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}
.delete-case-modal__card h2 {
  margin: 7px 0 10px;
  color: #3b485f;
}
.delete-case-modal__card p {
  margin: 0;
  color: #68758a;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.delete-case-modal__card p strong { color: #aa4652; }
.delete-case-modal__error {
  margin-top: 12px !important;
  padding: 10px 12px;
  color: #a84451 !important;
  background: #fff0ef;
  border-radius: 12px;
}
.delete-case-modal__card footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 22px;
}
.delete-case-modal__card footer button {
  min-height: 42px;
  padding: 10px 15px;
  color: #66748a;
  background: #f2f5f8;
  border: 0;
  border-radius: 13px;
  cursor: pointer;
  font-weight: 800;
}
.delete-case-modal__card footer button:last-child {
  color: #fff;
  background: linear-gradient(135deg, #dc7772, #be5864);
}
.delete-case-modal__card footer button:disabled {
  cursor: progress;
  opacity: .65;
}
@media (max-width: 1020px) {
  .overview-layout {
    grid-template-columns: 1fr;
    grid-template-rows: 160px minmax(0, 1fr);
    height: 810px;
  }
  .dispute-rail {
    grid-template-columns: 150px minmax(0, 1fr);
    grid-template-rows: minmax(0, 1fr);
    gap: 12px;
    padding: 14px;
    border-right: 0;
    border-bottom: 1px solid #dde8f5;
  }
  .dispute-rail__scroll {
    display: flex;
    gap: 12px;
    height: 100%;
    padding: 0 4px 0 0;
    overflow-x: auto;
    overflow-y: hidden;
    scroll-snap-type: x proximity;
  }
  .dispute-rail__heading {
    gap: 8px;
    align-items: flex-start;
    margin: 0;
  }
  .dispute-rail__heading h2 { font-size: 18px; line-height: 1.15; }
  .dispute-rail__heading strong { flex: 0 0 auto; }
  .dispute-ticket {
    height: 100%;
    min-width: 230px;
    gap: 3px;
    margin: 0;
    padding: 8px 10px;
    overflow: hidden;
  }
  .dispute-ticket__topline { font-size: 10px; }
  .dispute-ticket__topline small:first-child { padding: 2px 6px; }
  .dispute-ticket > strong {
    margin-top: 0;
    font-size: 14px;
    -webkit-line-clamp: 1;
  }
  .dispute-ticket__room,
  .dispute-ticket > small { font-size: 11px; }
  .hearing-adventure {
    --overview-journey-gap: 10px;
    grid-template-rows: auto auto minmax(104px, 1fr) 200px;
    padding: 18px;
  }
  .hearing-adventure__case-board { gap: 10px; }
  .hearing-adventure__case-board article {
    padding: 10px 12px;
    border-radius: 18px;
  }
  .hearing-adventure__next :deep(.digital-human) {
    grid-template-columns: 104px minmax(0, 1fr);
    gap: 12px;
    padding: 12px;
    border-radius: 22px;
  }
  .hearing-adventure__next :deep(.digital-human__portrait) { min-height: 104px; }
  .hearing-adventure__next :deep(.digital-human__portrait svg) {
    width: 104px;
    height: 104px;
  }
  .hearing-adventure__next :deep(.digital-human__identity strong) { font-size: 17px; }
  .hearing-adventure__next :deep(.digital-human__copy p) { margin: 8px 0; }
}
@media (max-width: 680px) {
  .overview-page__intro {
    flex-direction: column;
    gap: 14px;
    align-items: stretch;
  }
  .overview-page__actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }
  .overview-page__start,
  .overview-page__import,
  .overview-page__delete,
  .hearing-adventure__next > button {
    min-width: 0;
    min-height: 44px;
    justify-content: center;
    gap: 6px;
    padding: 10px 8px;
    font-size: 12px;
    white-space: nowrap;
  }
  .overview-page__start span,
  .overview-page__import span,
  .overview-page__delete span {
    width: 20px;
    height: 20px;
  }
  .overview-layout {
    grid-template-rows: 160px minmax(0, 1fr);
    height: 880px;
  }
  .dispute-rail {
    grid-template-columns: 104px minmax(0, 1fr);
    gap: 10px;
    padding: 12px;
  }
  .dispute-rail__heading {
    flex-direction: column;
    justify-content: center;
  }
  .dispute-rail__heading h2 { font-size: 15px; }
  .dispute-ticket { min-width: 210px; }
  .hearing-adventure {
    grid-template-rows: auto auto minmax(104px, 1fr) 208px;
    padding: 14px;
  }
  .hearing-adventure__header {
    flex-direction: column;
    gap: 6px;
    align-items: stretch;
  }
  .hearing-adventure__header h2 { font-size: 22px; }
  .hearing-adventure__next { grid-template-columns: 1fr; }
  .hearing-adventure__next :deep(.digital-human) {
    grid-template-columns: 84px minmax(0, 1fr);
    gap: 8px;
    padding: 10px;
    border-radius: 18px;
  }
  .hearing-adventure__next :deep(.digital-human__portrait) { min-height: 84px; }
  .hearing-adventure__next :deep(.digital-human__portrait svg) {
    width: 84px;
    height: 84px;
  }
  .hearing-adventure__case-board { gap: 8px; }
  .hearing-adventure__case-board article { padding: 9px 10px; }
  .intake-launcher__fields { grid-template-columns: 1fr; }
  .intake-launcher__claim { grid-template-columns: 1fr; }
  .intake-launcher__claim-copy { align-items: flex-start; flex-direction: column; }
  .intake-launcher__story { grid-column: auto; }
  .intake-launcher__card footer { align-items: stretch; flex-direction: column; }
}
@media (max-width: 360px) {
  .overview-page__actions { grid-template-columns: 1fr; }
  .overview-layout { height: 940px; }
  .dispute-rail {
    grid-template-columns: 88px minmax(0, 1fr);
    gap: 8px;
    padding: 10px;
  }
  .dispute-rail__heading h2 { font-size: 14px; }
  .dispute-rail__heading strong {
    width: 30px;
    height: 30px;
  }
  .dispute-ticket { min-width: 196px; }
  .hearing-adventure { padding: 12px; }
  .hearing-adventure__header h2 { font-size: 20px; }
}

/* Light Cognitive Field overview refactor */
.overview-page {
  --overview-rail-width: clamp(280px, 23vw, 330px);
  gap: 20px;
  padding: 0;
  background: transparent;
  border-radius: 0;
}
.overview-case-actions {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  grid-column: 1;
  grid-row: 1;
}
.overview-case-actions > button {
  width: 100%;
  min-width: 0;
  justify-content: center;
  padding-inline: 12px;
  white-space: nowrap;
}
.overview-guide-card {
  display: block;
  padding: 14px;
  min-width: 0;
  background: rgba(255, 255, 255, .9);
  border: 1px solid rgba(222, 235, 232, .94);
  border-radius: 32px;
  box-shadow: 0 16px 36px rgba(18, 56, 46, .1);
}
.overview-guide-card :deep(.digital-human) {
  min-width: 0;
  border: 0;
  box-shadow: none;
}
.overview-layout {
  display: grid;
  grid-template-columns: var(--overview-rail-width) minmax(0, 1fr);
  grid-template-rows: auto minmax(0, 1fr);
  column-gap: 20px;
  row-gap: 12px;
  height: 690px;
  min-width: 0;
  min-height: 0;
  overflow: visible;
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
.dispute-rail {
  grid-column: 1;
  grid-row: 2;
  min-width: 0;
  min-height: 0;
  padding: 20px;
  overflow: hidden;
  background: linear-gradient(180deg, rgba(255, 255, 255, .94), rgba(240, 250, 247, .94));
  border: 1px solid rgba(220, 234, 230, .95);
  border-radius: 30px;
  box-shadow: 0 16px 38px rgba(18, 56, 46, .1);
}
.dispute-ticket {
  background: rgba(255, 255, 255, .82);
  border-color: rgba(218, 232, 227, .9);
}
.dispute-ticket--active {
  border-color: #40c791;
  box-shadow: 0 13px 28px rgba(18, 56, 46, .13);
}
.hearing-adventure {
  position: relative;
  display: grid;
  grid-template-rows: 74px minmax(0, 1fr);
  gap: 14px;
  min-width: 0;
  min-height: 0;
  padding: 16px;
  overflow: hidden;
  background: rgba(255, 255, 255, .93);
  border: 1px solid rgba(224, 235, 232, .96);
  border-radius: 38px;
  box-shadow: 0 18px 44px rgba(18, 56, 46, .11);
  grid-column: 2;
  grid-row: 1 / span 2;
}
.overview-layout--empty .overview-empty {
  grid-column: 2;
  grid-row: 1 / span 2;
}
.hearing-adventure__header {
  position: relative;
  z-index: 3;
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 8px 12px;
  background: rgba(255, 255, 255, .94);
  border-radius: 23px;
  box-shadow: 0 10px 24px rgba(18, 56, 46, .09);
}
.hearing-adventure__identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
}
.hearing-adventure__identity > span {
  display: grid;
  flex: 0 0 auto;
  width: 42px;
  height: 42px;
  place-items: center;
  color: #fff;
  background: #40c791;
  border-radius: 15px;
  font-size: 24px;
  font-weight: 900;
  letter-spacing: 0;
}
.hearing-adventure__identity > div { min-width: 0; }
.hearing-adventure__identity small {
  color: #526e6b;
  font-size: 9px;
  font-weight: 900;
  letter-spacing: .14em;
}
.hearing-adventure__identity h2 {
  margin: 1px 0 2px;
  overflow: hidden;
  color: #142e2e;
  font-size: 17px;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hearing-adventure__header-status {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
}
.hearing-adventure__header-status > strong {
  padding: 9px 14px;
  color: #142e2e;
  background: #fff2ba;
  border-radius: 999px;
  font-size: 12px;
  white-space: nowrap;
}
.hearing-adventure__viewport {
  min-width: 0;
  min-height: 0;
  overflow-x: auto;
  overflow-y: hidden;
  border-radius: 28px;
  scrollbar-width: thin;
}
.hearing-adventure__map {
  position: relative;
  min-width: 1000px;
  height: 570px;
  overflow: hidden;
  background: rgba(252, 254, 253, .9);
  border-radius: 28px;
}
.hearing-adventure__terrain {
  position: absolute;
  z-index: 0;
  display: block;
  border-radius: 50%;
  pointer-events: none;
}
.hearing-adventure__terrain--mint {
  left: -95px;
  bottom: -115px;
  width: 500px;
  height: 350px;
  background: #d1f7e5;
}
.hearing-adventure__terrain--yellow {
  top: -175px;
  right: -90px;
  width: 510px;
  height: 390px;
  background: #fff2ba;
}
.hearing-adventure__terrain--blue {
  right: 120px;
  bottom: -165px;
  width: 440px;
  height: 300px;
  background: #d6e8ff;
}
.adventure-path__route {
  position: absolute;
  inset: 80px 0 auto;
  z-index: 2;
  width: 100%;
  height: 330px;
  overflow: visible;
}
.adventure-path__route path {
  fill: none;
  stroke: #40c791;
  stroke-width: 7;
  stroke-linecap: round;
  stroke-dasharray: 5 18;
}
.adventure-path {
  position: absolute;
  inset: 0;
  z-index: 3;
  display: block;
  width: auto;
  min-width: 0;
  min-height: 0;
  margin: 0;
  padding: 0;
  overflow: visible;
  list-style: none;
}
.adventure-path li {
  position: absolute;
  display: grid;
  box-sizing: border-box;
  width: 116px;
  height: 126px;
  grid-template-rows: 52px auto auto;
  justify-items: center;
  align-content: start;
  gap: 3px;
  padding: 10px 8px;
  color: #142e2e;
  background: #fff;
  border: 4px solid #fff;
  border-radius: 38px;
  box-shadow: 0 10px 24px rgba(18, 56, 46, .13);
  transition: transform .2s ease, opacity .2s ease;
}
.adventure-path__hit {
  position: absolute;
  inset: 0;
  z-index: 4;
  padding: 0;
  cursor: pointer;
  background: transparent;
  border: 0;
  border-radius: 34px;
}
.adventure-path__hit:focus-visible { outline: 3px solid rgba(64, 140, 247, .42); outline-offset: 3px; }
.adventure-path__hit:disabled { cursor: not-allowed; }
.adventure-path__hit[data-permission-locked="true"] { cursor: not-allowed; }
.adventure-path li[data-stage-state="completed"]:has(.adventure-path__hit:not(:disabled)):hover {
  transform: translateY(-4px);
  box-shadow: 0 14px 28px rgba(18, 56, 46, .17);
}
.adventure-path__error {
  position: absolute;
  top: 116px;
  left: 22px;
  z-index: 5;
  max-width: 280px;
  margin: 0;
  color: #a23e49;
  font-size: 12px;
}
.adventure-path li:nth-child(1) { left: 76px; top: 294px; background: #ffd9cf; }
.adventure-path li:nth-child(2) { left: 224px; top: 150px; background: #d6e8ff; }
.adventure-path li:nth-child(3) { left: 382px; top: 252px; background: #d1f7e5; }
.adventure-path li:nth-child(4) { left: 548px; top: 224px; background: #e8deff; }
.adventure-path li:nth-child(5) { left: 708px; top: 100px; background: #fff2ba; }
.adventure-path li:nth-child(6) { left: 866px; top: 206px; background: #fff; }
.adventure-path li[data-stage-state="locked"] {
  color: #9da8b6;
  background: #f4f7fa;
  border-color: #fff;
  box-shadow: 0 8px 20px rgba(78, 99, 128, .075);
  filter: saturate(.72);
}
.adventure-path li[data-stage-state="locked"] > * { opacity: .78; }
.adventure-path li:nth-child(1)[data-stage-state="locked"] { background: #fff0eb; border-color: #fff8f5; }
.adventure-path li:nth-child(2)[data-stage-state="locked"] { background: #edf5ff; border-color: #f8fbff; }
.adventure-path li:nth-child(3)[data-stage-state="locked"] { background: #ebfaf3; border-color: #f7fdf9; }
.adventure-path li:nth-child(4)[data-stage-state="locked"] { background: #f3efff; border-color: #fbf9ff; }
.adventure-path li:nth-child(5)[data-stage-state="locked"] { background: #fff8da; border-color: #fffdf3; }
.adventure-path li:nth-child(6)[data-stage-state="locked"] { background: #edf5f3; border-color: #f8fcfb; }
.adventure-path li[data-stage-state="current"] {
  z-index: 3;
  outline: 3px solid rgba(64, 199, 145, .28);
  transform: translateY(-7px) scale(1.06);
}
.adventure-path__orb {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  color: #fff;
  background: #ff6b59;
  border-radius: 50%;
  font-size: 14px;
  font-weight: 900;
}
.adventure-path li:nth-child(2) .adventure-path__orb { background: #408cf7; }
.adventure-path li:nth-child(3) .adventure-path__orb { background: #40c791; }
.adventure-path li:nth-child(4) .adventure-path__orb { background: #9168e7; }
.adventure-path li:nth-child(5) .adventure-path__orb { color: #142e2e; background: #ffd447; }
.adventure-path li:nth-child(6) .adventure-path__orb { background: #142e2e; }
.adventure-path li > strong { color: #142e2e; font-size: 15px; }
.adventure-path li > small { color: #526e6b; font-size: 10px; }
.adventure-path li[data-stage-state="locked"] .adventure-path__orb {
  color: #748296;
  background: #e1e8ef;
}
.adventure-path li:nth-child(1)[data-stage-state="locked"] .adventure-path__orb { color: #9e584d; background: #ffd8cf; }
.adventure-path li:nth-child(2)[data-stage-state="locked"] .adventure-path__orb { color: #4f78a6; background: #d6e8ff; }
.adventure-path li:nth-child(3)[data-stage-state="locked"] .adventure-path__orb { color: #3e8668; background: #d1f1e1; }
.adventure-path li:nth-child(4)[data-stage-state="locked"] .adventure-path__orb { color: #7257a9; background: #e2d8f8; }
.adventure-path li:nth-child(5)[data-stage-state="locked"] .adventure-path__orb { color: #8a7629; background: #f7e9a8; }
.adventure-path li:nth-child(6)[data-stage-state="locked"] .adventure-path__orb { color: #527069; background: #dce9e6; }
.adventure-path li[data-stage-state="locked"] > strong { color: #667487; }
.adventure-path li[data-stage-state="locked"] > small { color: #8995a5; }
.map-room-entry {
  position: absolute;
  top: 25px;
  left: 22px;
  z-index: 4;
  display: flex;
  width: 224px;
  min-height: 88px;
  box-sizing: border-box;
  align-items: center;
  gap: 13px;
  padding: 13px 16px;
  color: #142e2e;
  text-align: left;
  background: #ffd9cf;
  border: 0;
  border-radius: 30px;
  box-shadow: 0 10px 24px rgba(18, 56, 46, .11);
  cursor: pointer;
}
.map-room-entry:hover { transform: translateY(-2px); }
.map-room-entry:focus-visible { outline: 3px solid rgba(64, 140, 247, .32); outline-offset: 3px; }
.map-room-entry:disabled {
  color: #667487;
  background: #edf2f6;
  box-shadow: 0 8px 18px rgba(70, 89, 112, .08);
  cursor: not-allowed;
}
.map-room-entry:disabled:hover { transform: none; }
.map-room-entry:disabled > span:first-child {
  color: #748296;
  background: #fff;
}
.map-room-entry > span:first-child {
  display: grid;
  flex: 0 0 auto;
  width: 52px;
  height: 52px;
  place-items: center;
  color: #142e2e;
  background: rgba(255, 255, 255, .68);
  border-radius: 50%;
  font-size: 25px;
  font-weight: 900;
}
.map-room-entry > span:last-child {
  display: grid;
  min-width: 0;
  gap: 5px;
}
.map-room-entry strong { font-size: 14px; }
.map-room-entry small {
  overflow: hidden;
  color: #526e6b;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hearing-adventure__finish {
  position: absolute;
  right: 24px;
  bottom: 34px;
  z-index: 3;
  display: grid;
  justify-items: center;
  gap: 4px;
  color: #142e2e;
}
.hearing-adventure__finish span { color: #ffd447; font-size: 52px; -webkit-text-stroke: 2px #142e2e; }
.hearing-adventure__finish strong { font-size: 12px; }
@media (max-width: 1020px) {
  .overview-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 180px 690px;
    height: auto;
  }
  .overview-case-actions { grid-column: 1; grid-row: 1; width: min(100%, 330px); }
  .dispute-rail {
    grid-column: 1;
    grid-row: 2;
    grid-template-columns: 150px minmax(0, 1fr);
    grid-template-rows: minmax(0, 1fr);
    border: 1px solid rgba(220, 234, 230, .95);
  }
  .hearing-adventure { min-height: 690px; grid-column: 1; grid-row: 3; }
  .overview-layout--empty .overview-empty { grid-column: 1; grid-row: 2 / span 2; }
}
@media (max-width: 760px) {
  .overview-layout { grid-template-rows: auto 166px 660px; }
  .hearing-adventure {
    min-height: 660px;
    padding: 12px;
    border-radius: 30px;
  }
  .hearing-adventure__header { align-items: stretch; flex-direction: column; height: auto; }
  .hearing-adventure__header-status { justify-content: space-between; }
  .hearing-adventure__map { height: 545px; }
}
@media (max-width: 420px) {
  .overview-guide-card { padding: 8px; border-radius: 24px; }
  .overview-layout { grid-template-rows: auto 156px 650px; }
  .dispute-rail { grid-template-columns: 92px minmax(0, 1fr); padding: 10px; border-radius: 22px; }
  .hearing-adventure { min-height: 650px; }
  .hearing-adventure__identity h2 { max-width: 220px; }
}
</style>
