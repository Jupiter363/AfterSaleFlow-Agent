<script setup>
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { disputeApi } from "../../api/disputes";
import { actor } from "../../state/actor";

const props = defineProps({
  initialOutcome: { type: Object, default: null },
});

const route = useRoute();
const router = useRouter();
const outcome = ref(props.initialOutcome);
const error = ref("");
const caseId = computed(
  () => outcome.value?.case_id || route.params.caseId,
);
const actions = computed(() => outcome.value?.actions || []);
const decision = computed(
  () =>
    outcome.value?.final_decision || {
      conclusion: "平台终审结果已生成",
      explanation: "最终裁决与执行结果以平台留存的审核记录为准。",
    },
);

const actionLabels = {
  REFUND: "退款",
  COMPENSATE: "补偿",
  NOTIFY_MERCHANT: "通知商家",
  NOTIFY_USER: "通知用户",
  CLOSE_CASE: "关闭案件",
};
const statusLabels = {
  SUCCEEDED: "执行成功",
  PENDING: "等待执行",
  RUNNING: "执行中",
  FAILED: "执行失败",
  MANUAL_REQUIRED: "等待人工处理",
};
const resultLabels = {
  amount: "金额",
  currency: "币种",
  delivered: "已送达",
  operation: "操作",
  reference_id: "引用号",
  simulated: "模拟执行",
  tool_name: "工具",
  "response.action_type": "动作类型",
  "response.idempotency_key": "幂等键",
  "response.status": "回执状态",
  status: "状态",
};

function resultValue(value) {
  if (value === null || value === undefined || value === "") return "未提供";
  if (typeof value === "boolean") return value ? "是" : "否";
  return String(value);
}

function flattenResult(value, prefix = "") {
  if (value === null || value === undefined || value === "") return [];
  if (Array.isArray(value)) {
    return value.flatMap((item, index) =>
      flattenResult(item, prefix ? `${prefix}.${index + 1}` : `结果 ${index + 1}`),
    );
  }
  if (typeof value === "object") {
    return Object.entries(value).flatMap(([key, item]) =>
      flattenResult(item, prefix ? `${prefix}.${key}` : key),
    );
  }
  return [
    {
      label: resultLabels[prefix] || prefix,
      value: resultValue(value),
    },
  ];
}

function executionResultRows(action) {
  return flattenResult(action.result);
}

async function load() {
  try {
    if (outcome.value === null) {
      outcome.value = await disputeApi.outcome(actor, caseId.value);
    }
  } catch (failure) {
    error.value = failure.message;
  }
}

onMounted(load);
</script>

<template>
  <main class="outcome-page">
    <section class="outcome-hero">
      <div class="outcome-hero__seal" aria-hidden="true">
        <span>⚖️</span>
        <i>FINAL</i>
      </div>
      <div>
        <span class="outcome-kicker">THE VERDICT HAS LANDED</span>
        <h1>最终裁决</h1>
        <p>{{ outcome?.title }} · {{ caseId }}</p>
      </div>
      <div class="outcome-hero__status">
        <strong>裁决已生效</strong>
        <small>{{ outcome?.closed_at || "执行结果持续同步中" }}</small>
      </div>
    </section>

    <section class="verdict-card">
      <span class="outcome-kicker">PLAIN-LANGUAGE RULING</span>
      <h2>{{ decision.conclusion }}</h2>
      <p>{{ decision.explanation }}</p>
      <p v-if="decision.review_reason" class="verdict-card__review">
        审核说明：{{ decision.review_reason }}
      </p>
      <div class="verdict-card__boundary">
        {{
          decision.human_confirmed
            ? "此处展示的是平台审核员确认后的最终裁决，不是 AI 草案。"
            : "此处展示的是已生效的系统结案结果。"
        }}
      </div>
    </section>

    <section class="execution-board">
      <header>
        <div>
          <span class="outcome-kicker">EXECUTION RECEIPTS</span>
          <h2>裁决落地轨迹</h2>
        </div>
        <span>{{ actions.length }} 项执行动作</span>
      </header>

      <div v-if="actions.length" class="execution-board__grid">
        <article
          v-for="(action, index) in actions"
          :key="action.action_record_id"
          data-execution-receipt
        >
          <div class="execution-receipt__step">{{ index + 1 }}</div>
          <div>
            <span>{{ actionLabels[action.action_type] || action.action_type }}</span>
            <h3>{{ statusLabels[action.execution_status] || action.execution_status }}</h3>
            <p v-if="action.external_result_ref">
              外部回执：{{ action.external_result_ref }}
            </p>
            <dl v-if="executionResultRows(action).length" class="execution-result">
              <div
                v-for="row in executionResultRows(action)"
                :key="`${action.action_record_id}-${row.label}`"
              >
                <dt>{{ row.label }}</dt>
                <dd>{{ row.value }}</dd>
              </div>
            </dl>
          </div>
          <i :data-status="action.execution_status">
            {{ action.execution_status === "SUCCEEDED" ? "✓" : "…" }}
          </i>
        </article>
      </div>
      <div v-else class="execution-board__empty">执行回执正在路上，请稍后刷新。</div>
    </section>

    <footer class="outcome-footer">
      <div>
        <span aria-hidden="true">📬</span>
        <p>裁决、执行成功或异常都会同步进入双方平台信箱。</p>
      </div>
      <button type="button" @click="router.push('/disputes')">返回争议订单中心</button>
    </footer>
    <p v-if="error" class="outcome-error" role="alert">{{ error }}</p>
  </main>
</template>

<style scoped>
.outcome-page { display: grid; gap: 18px; }
.outcome-hero {
  display: grid; grid-template-columns: auto 1fr auto; gap: 20px; align-items: center;
  padding: 28px; background: linear-gradient(135deg, #e7f8ef, #eaf6ff 55%, #f1ebff);
  border: 1px solid #d7e8e4; border-radius: 34px;
}
.outcome-hero__seal { display: grid; place-items: center; width: 94px; height: 94px; background: #fff; border-radius: 50%; box-shadow: inset 0 0 0 7px #eef8f1; }
.outcome-hero__seal span { font-size: 42px; }
.outcome-hero__seal i { color: #5d8a76; font-size: 9px; font-style: normal; font-weight: 900; letter-spacing: .15em; }
.outcome-kicker { color: #7186a5; font-size: 10px; font-weight: 900; letter-spacing: .17em; }
.outcome-hero h1 { margin: 6px 0; color: #2f4058; font-size: clamp(34px, 5vw, 52px); }
.outcome-hero p { margin: 0; color: #728097; }
.outcome-hero__status { display: grid; gap: 5px; padding: 13px; color: #267251; background: #fff; border-radius: 16px; }
.outcome-hero__status small { color: #7f8c9d; }
.verdict-card { padding: 25px; background: #ffffffde; border: 1px solid #e1e8f1; border-radius: 28px; text-align: center; }
.verdict-card h2 { margin: 9px 0; color: #34445d; font-size: clamp(24px, 4vw, 37px); }
.verdict-card > p { max-width: 760px; margin: 0 auto; color: #64738a; line-height: 1.8; }
.verdict-card__review { margin-top: 10px !important; color: #526b86 !important; font-size: 12px; }
.verdict-card__boundary { width: max-content; max-width: 100%; padding: 8px 12px; margin: 18px auto 0; color: #725f8d; background: #f1edff; border-radius: 999px; font-size: 11px; }
.execution-board { padding: 21px; background: #ffffffce; border: 1px solid #e1e8f1; border-radius: 28px; }
.execution-board header { display: flex; justify-content: space-between; gap: 16px; }
.execution-board h2 { margin: 5px 0; color: #34445d; }
.execution-board header > span { height: max-content; padding: 6px 9px; color: #5a738f; background: #edf5fb; border-radius: 999px; font-size: 11px; }
.execution-board__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 15px; }
.execution-board article { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; padding: 15px; background: linear-gradient(145deg, #f8fbff, #fff); border: 1px solid #e3eaf2; border-radius: 18px; }
.execution-receipt__step { display: grid; place-items: center; width: 34px; height: 34px; color: #fff; background: #7f8fe0; border-radius: 11px; font-weight: 900; }
.execution-board article span { color: #7a889c; font-size: 10px; }
.execution-board article h3 { margin: 4px 0; color: #405069; }
.execution-board article p { color: #6d7a8e; font-size: 11px; }
.execution-result { display: grid; gap: 5px; margin: 8px 0 0; }
.execution-result div { display: grid; grid-template-columns: 70px 1fr; gap: 8px; align-items: start; padding: 5px 7px; background: #eef5fb; border-radius: 9px; }
.execution-result dt { color: #708196; font-size: 10px; font-weight: 800; }
.execution-result dd { margin: 0; overflow-wrap: anywhere; color: #405069; font-size: 11px; }
.execution-board article > i { display: grid; place-items: center; width: 27px; height: 27px; color: #267252; background: #def5e8; border-radius: 50%; font-style: normal; }
.execution-board article > i:not([data-status="SUCCEEDED"]) { color: #9a6d25; background: #fff0cc; }
.execution-board__empty { padding: 35px; color: #8290a3; text-align: center; }
.outcome-footer { display: flex; justify-content: space-between; gap: 20px; align-items: center; padding: 17px 20px; background: #fff5dc; border: 1px solid #efdfb8; border-radius: 20px; }
.outcome-footer div { display: flex; gap: 10px; align-items: center; color: #6f665f; }
.outcome-footer p { margin: 0; }
.outcome-footer button { padding: 10px 14px; color: white; background: linear-gradient(135deg, #5dbb92, #5aa8c9); border: 0; border-radius: 12px; cursor: pointer; font-weight: 800; }
.outcome-error { color: #a64551; }
@media (max-width: 720px) {
  .outcome-hero { grid-template-columns: auto 1fr; }
  .outcome-hero__status { grid-column: 1 / -1; }
  .execution-board__grid { grid-template-columns: 1fr; }
  .outcome-footer { align-items: stretch; flex-direction: column; }
}
</style>
