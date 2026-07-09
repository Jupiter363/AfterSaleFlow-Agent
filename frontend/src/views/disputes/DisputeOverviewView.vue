<script setup>
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { disputeApi } from "../../api/disputes";
import DigitalHuman from "../../components/avatar/DigitalHuman.vue";
import PhaseCountdown from "../../components/room/PhaseCountdown.vue";
import { actor } from "../../state/actor";
import {
  disputeStore,
  loadDisputes,
} from "../../stores/dispute";

const props = defineProps({
  initialCases: { type: Array, default: () => [] },
  serverNow: { type: String, default: () => new Date().toISOString() },
  createAction: { type: Function, default: null },
  simulateExternalImportAction: { type: Function, default: null },
});

const router = useRouter();
const localCases = ref([...props.initialCases]);
const selectedId = ref(props.initialCases[0]?.id || null);
const intakeOpen = ref(false);
const creating = ref(false);
const importingExternal = ref(false);
const createError = ref("");
const importError = ref("");
const intakeForm = ref({
  orderReference: "",
  afterSalesReference: "",
  logisticsReference: "",
  userId: "",
  merchantId: "",
  requestedResolution: "VERIFY_OR_EXPLAIN_ONLY",
  requestedAmount: "",
  requestedItems: "",
  requestReason: "",
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
const selectedCase = computed(
  () =>
    cases.value.find((item) => item.id === selectedId.value) ||
    cases.value[0] ||
    null,
);

const journey = [
  { room: "INTAKE", label: "争议接待室", icon: "☁" },
  { room: "EVIDENCE", label: "证据书记官室", icon: "⌕" },
  { room: "HEARING", label: "共享小法庭", icon: "⚖" },
  { room: "REVIEW", label: "平台终审", icon: "◇" },
  { room: "OUTCOME", label: "结果与执行", icon: "✓" },
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
  if (selectedCase.value?.case_status === "CLOSED") return journey.length - 1;
  return Math.max(
    0,
    journey.findIndex(
      (stage) => stage.room === selectedCase.value?.current_room,
    ),
  );
});

function stageState(index) {
  if (index < currentRoomIndex.value) return "completed";
  if (index === currentRoomIndex.value) return "current";
  return "locked";
}

function enterCurrentRoom() {
  const dispute = selectedCase.value;
  if (!dispute) return;
  const routes = {
    INTAKE: "intake",
    EVIDENCE: "evidence",
    HEARING: "hearing",
    REVIEW: "outcome",
  };
  const room = dispute.case_status === "CLOSED"
    ? "outcome"
    : routes[dispute.current_room] || "outcome";
  router.push(`/disputes/${dispute.id}/${room}`);
}

function sourceLabel(source) {
  return source === "EXTERNAL_IMPORT" ? "外部导入" : "接待官创建";
}

function shortId(value) {
  if (!value) return "未关联";
  return value.length > 16 ? `${value.slice(0, 9)}…` : value;
}

function roomLabel(room) {
  return journey.find((stage) => stage.room === room)?.label || room || "待分配房间";
}

function riskLabel(risk) {
  return riskLabels[risk] || risk || "未评估";
}

function pendingActionLabel(action) {
  return pendingActionLabels[action] || action || "等待下一步";
}

function openIntake() {
  intakeForm.value.userId = actor.role === "USER" ? actor.id : "";
  intakeForm.value.merchantId =
    actor.role === "MERCHANT" ? actor.id : "";
  intakeForm.value.requestedResolution = "VERIFY_OR_EXPLAIN_ONLY";
  intakeForm.value.requestedAmount = "";
  intakeForm.value.requestedItems = "";
  intakeForm.value.requestReason = "";
  createError.value = "";
  intakeOpen.value = true;
}

function simulatedCounterpartyId(role) {
  return role === "MERCHANT" ? "user-local" : "merchant-local";
}

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

async function simulateExternalImport() {
  const initiatorRole = actor.role === "MERCHANT" ? "MERCHANT" : "USER";
  const command = {
    count: 2,
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
  try {
    const result = props.simulateExternalImportAction
      ? await props.simulateExternalImportAction(command)
      : await disputeApi.simulateExternalImport(actor, command);
    const imported = (result.items || []).map(normalizeImportedCase);
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
  } catch (failure) {
    importError.value = failure.message;
  } finally {
    importingExternal.value = false;
  }
}

async function createDispute() {
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
      request_reason: intakeForm.value.requestReason || intakeForm.value.description,
      original_statement: intakeForm.value.description,
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
      <div class="overview-page__actions">
        <button
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
          class="overview-page__start"
          type="button"
          data-start-dispute
          @click="openIntake"
        >
          <span aria-hidden="true">＋</span>
          发起争议审理
        </button>
      </div>
    </header>
    <p v-if="importError" class="overview-page__error" role="alert">{{ importError }}</p>

    <div v-if="cases.length" class="overview-layout">
      <aside class="dispute-rail" aria-label="争议订单">
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
            @click="selectedId = item.id"
          >
            <span class="dispute-ticket__topline">
              <small>{{ sourceLabel(item.source_type) }}</small>
              <small :data-risk="item.risk_level">{{ riskLabel(item.risk_level) }}</small>
            </span>
            <strong>{{ item.title }}</strong>
            <span class="dispute-ticket__id">
              <span :title="item.order_id">{{ shortId(item.order_id) }}</span>
              ·
              <span data-short-case-id :title="item.id">{{ shortId(item.id) }}</span>
            </span>
            <span class="dispute-ticket__room">
              <i aria-hidden="true" />
              {{ roomLabel(item.current_room) }}
            </span>
            <small>{{ pendingActionLabel(item.pending_action) }}</small>
          </button>
        </div>
      </aside>

      <main class="hearing-adventure" data-hearing-adventure>
        <div class="hearing-adventure__sky" aria-hidden="true">
          <span>✦</span><span>☁</span><span>✦</span>
        </div>
        <header class="hearing-adventure__header">
          <div>
            <span>CASE JOURNEY</span>
            <h2>{{ selectedCase?.title }}</h2>
            <p>{{ pendingActionLabel(selectedCase?.pending_action) }}</p>
          </div>
          <PhaseCountdown
            v-if="selectedCase?.deadline_at"
            :deadline-at="selectedCase.deadline_at"
            :server-now="serverNow"
            label="当前房间剩余"
          />
        </header>

        <div class="hearing-adventure__next" data-overview-guide>
          <DigitalHuman
            state="HANDOFF"
            name="小衡"
            role="路线引导员"
            :message="pendingActionLabel(selectedCase?.pending_action) || '请选择一个争议案件。'"
          />
          <button type="button" data-enter-current-room @click="enterCurrentRoom">
            进入当前房间
            <span aria-hidden="true">→</span>
          </button>
        </div>

        <ol class="adventure-path" data-adventure-path>
          <li
            v-for="(stage, index) in journey"
            :key="stage.room"
            :data-stage-state="stageState(index)"
          >
            <span class="adventure-path__connector" aria-hidden="true" />
            <span class="adventure-path__node" aria-hidden="true">{{ stage.icon }}</span>
            <div>
              <small>STAGE {{ index + 1 }}</small>
              <strong>{{ stage.label }}</strong>
              <span v-if="stageState(index) === 'completed'">已经完成并留痕</span>
              <span v-else-if="stageState(index) === 'current'">你现在在这里</span>
              <span v-else>完成前序阶段后开放</span>
            </div>
          </li>
        </ol>

        <section
          class="hearing-adventure__case-board"
          data-case-journey-dashboard
          aria-label="当前案件态势"
        >
          <article>
            <span>CASE FILE</span>
            <strong>{{ selectedCase?.id }}</strong>
            <small>案件编号</small>
          </article>
          <article>
            <span>ORDER</span>
            <strong>{{ selectedCase?.order_id || "未关联订单" }}</strong>
            <small>{{ sourceLabel(selectedCase?.source_type) }}</small>
          </article>
          <article>
            <span>ROOM CODE</span>
            <strong>{{ selectedCase?.current_room }}</strong>
            <small>{{ roomLabel(selectedCase?.current_room) }}</small>
          </article>
          <article>
            <span>NEXT ACTION</span>
            <strong>{{ pendingActionLabel(selectedCase?.pending_action) }}</strong>
            <small>{{ riskLabel(selectedCase?.risk_level) }}</small>
          </article>
        </section>
      </main>
    </div>

    <div v-else class="overview-empty">
      <span aria-hidden="true">🎟</span>
      <h2>还没有争议订单</h2>
      <p>外部导入或接待官创建的争议会出现在这里，普通订单不会进入本页。</p>
    </div>

    <div v-if="intakeOpen" class="intake-launcher" role="dialog" aria-modal="true">
      <form class="intake-launcher__card" @submit.prevent="createDispute">
        <header>
          <div>
            <span>NEW DISPUTE TICKET</span>
            <h2>请争议接待官开一张新案卡</h2>
            <p>先提供最少引用与一段自然语言陈述，进入接待室后再由数字人继续追问。</p>
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
            <label class="intake-launcher__claim-reason">
              诉求原因说明
              <textarea
                v-model="intakeForm.requestReason"
                data-claim-request-reason
                rows="3"
                required
                placeholder="例如：物流显示签收但本人未收到，希望平台核验后退款"
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
              placeholder="像和业务员说话一样，描述履约争议与期望结果"
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
  </section>
</template>

<style scoped>
.overview-page { display: grid; gap: 24px; }
.overview-page__intro {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-end;
}
.overview-page__actions {
  display: flex;
  flex-wrap: wrap;
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
  color: #263754;
  font-size: clamp(32px, 5vw, 56px);
  line-height: 1.05;
}
.overview-page__lead {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
  align-items: center;
  margin: 0;
  color: #738097;
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
  height: clamp(660px, calc(100vh - 210px), 780px);
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
  min-height: 0;
  overflow: hidden;
  background: linear-gradient(180deg, #f0f8ff, #fff8f2);
  border-right: 1px solid #dde8f5;
}
.dispute-rail__scroll {
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
}
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
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.dispute-ticket > span,
.dispute-ticket > small { color: #7a879b; }
.dispute-ticket__id {
  display: flex;
  gap: 4px;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}
.dispute-ticket__id span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dispute-ticket__room { display: flex; align-items: center; gap: 7px; font-weight: 800; }
.dispute-ticket__room i { width: 8px; height: 8px; background: #61c997; border-radius: 50%; }
.hearing-adventure {
  --overview-journey-gap: 16px;
  position: relative;
  display: grid;
  grid-template-rows: auto auto minmax(160px, 220px) minmax(160px, 1fr);
  gap: var(--overview-journey-gap);
  padding: 28px;
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
}
.hearing-adventure__header h2 { margin: 5px 0; color: #2c3e5b; font-size: 28px; }
.hearing-adventure__header p { margin: 0; color: #748298; }
.hearing-adventure__case-board {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  align-self: end;
}
.hearing-adventure__case-board article {
  display: grid;
  gap: 5px;
  min-height: 160px;
  min-width: 0;
  padding: 16px;
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
  min-width: 0;
  overflow: hidden;
  color: #2d3e5c;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hearing-adventure__case-board small { color: #73839a; }
.adventure-path {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(5, minmax(115px, 1fr));
  align-items: center;
  gap: 4px;
  align-self: stretch;
  margin: 0;
  min-height: 112px;
  padding: 8px 0;
  list-style: none;
}
.adventure-path li {
  position: relative;
  display: grid;
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
  padding: 14px;
  border: 1px solid rgba(114, 139, 184, .28);
  border-radius: 22px;
  background: linear-gradient(135deg, rgba(244, 249, 255, .96), rgba(255, 249, 235, .9));
}
.intake-launcher__claim-copy,
.intake-launcher__claim-reason {
  grid-column: 1 / -1;
}
.intake-launcher__claim-copy {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}
.intake-launcher__claim-copy span {
  color: #31527d;
  font-weight: 900;
  letter-spacing: .08em;
}
.intake-launcher__claim-copy p {
  margin: 0;
  color: #7b879a;
  font-size: 12px;
}
.intake-launcher__story { grid-column: 1 / -1; }
.intake-launcher__card footer { display: flex; justify-content: space-between; gap: 18px; align-items: center; margin-top: 15px; }
.intake-launcher__card footer span { color: #7d899b; font-size: 11px; }
.intake-launcher__card footer button {
  padding: 11px 16px; color: #fff; background: linear-gradient(135deg, #ff8a70, #8a86ee); border: 0; border-radius: 13px; cursor: pointer; font-weight: 800;
}
.intake-launcher__error { color: #a84451; }
@media (max-width: 1020px) {
  .overview-layout {
    grid-template-columns: 1fr;
    height: auto;
    min-height: 0;
    overflow: visible;
  }
  .dispute-rail {
    grid-template-rows: auto auto;
    overflow: hidden;
    border-right: 0;
    border-bottom: 1px solid #dde8f5;
  }
  .dispute-rail__scroll {
    display: flex;
    gap: 12px;
    overflow-x: auto;
    overflow-y: hidden;
    scroll-snap-type: x proximity;
  }
  .dispute-rail__heading { min-width: 180px; }
  .dispute-ticket { min-width: 270px; margin: 0; }
  .hearing-adventure__case-board { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .adventure-path { grid-template-columns: 1fr; align-items: stretch; padding-left: 10px; }
  .adventure-path li { grid-template-columns: 68px 1fr; justify-items: start; text-align: left; }
  .adventure-path__node { width: 58px; height: 58px; border-radius: 21px; }
  .adventure-path__connector { top: 50%; left: 28px; width: 7px; height: 100%; }
  .adventure-path li div { align-self: center; }
}
@media (max-width: 680px) {
  .overview-page__intro,
  .hearing-adventure__header,
  .hearing-adventure__next { grid-template-columns: 1fr; flex-direction: column; align-items: stretch; }
  .overview-page__start,
  .overview-page__import,
  .hearing-adventure__next > button { justify-content: center; }
  .overview-page__actions { justify-content: stretch; }
  .hearing-adventure { padding: 18px; }
  .hearing-adventure__case-board { grid-template-columns: 1fr; }
  .intake-launcher__fields { grid-template-columns: 1fr; }
  .intake-launcher__claim { grid-template-columns: 1fr; }
  .intake-launcher__claim-copy { align-items: flex-start; flex-direction: column; }
  .intake-launcher__story { grid-column: auto; }
  .intake-launcher__card footer { align-items: stretch; flex-direction: column; }
}
</style>
