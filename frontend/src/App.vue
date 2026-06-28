<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { reviewApi } from "./api/review";

const actor = ref({ id: "reviewer-local", role: "PLATFORM_REVIEWER" });
const tasks = ref([]);
const selectedTask = ref(null);
const packet = ref(null);
const loading = ref(false);
const reason = ref("");
const modifiedPlan = ref("{}");
const canDecide = computed(() =>
  ["PLATFORM_REVIEWER", "ADMIN"].includes(actor.value.role),
);

async function loadTasks() {
  loading.value = true;
  try {
    tasks.value = await reviewApi.list(actor.value);
  } catch (error) {
    ElMessage.error(error.message);
  } finally {
    loading.value = false;
  }
}

async function selectTask(task) {
  selectedTask.value = task;
  packet.value = await reviewApi.packet(actor.value, task.id);
  modifiedPlan.value = JSON.stringify(packet.value.remedy, null, 2);
}

async function decide(decision) {
  if (!canDecide.value) {
    ElMessage.error("当前角色无权提交审核决定");
    return;
  }
  if (!reason.value.trim()) {
    ElMessage.warning("请填写审核理由");
    return;
  }
  let approvedPlan = null;
  if (decision === "MODIFY_AND_APPROVE") {
    try {
      approvedPlan = JSON.parse(modifiedPlan.value);
    } catch {
      ElMessage.error("修改后的方案不是有效 JSON");
      return;
    }
  }
  await ElMessageBox.confirm(
    `确认提交 ${decision}？此操作将写入不可绕过的审核审计。`,
    "二次确认",
    { type: "warning" },
  );
  await reviewApi.decide(actor.value, selectedTask.value.id, {
    decision,
    reason: reason.value,
    approved_plan: approvedPlan,
  });
  ElMessage.success("审核决定已提交");
  packet.value = null;
  selectedTask.value = null;
  reason.value = "";
  await loadTasks();
}

onMounted(loadTasks);
</script>

<template>
  <main class="shell">
    <header class="hero">
      <div>
        <p class="eyebrow">HUMAN-GATED OPERATIONS</p>
        <h1>订单履约争议审核台</h1>
        <p>证据、规则、草案与执行方案在一个责任界面完成核验。</p>
      </div>
      <div class="identity">
        <el-input v-model="actor.id" aria-label="审核员 ID" />
        <el-select v-model="actor.role" aria-label="角色" @change="loadTasks">
          <el-option label="平台审核员" value="PLATFORM_REVIEWER" />
          <el-option label="管理员" value="ADMIN" />
          <el-option label="客服（只读）" value="CUSTOMER_SERVICE" />
        </el-select>
      </div>
    </header>

    <section class="workspace">
      <aside class="task-panel">
        <div class="panel-title">
          <h2>待审核任务</h2>
          <el-button text @click="loadTasks">刷新</el-button>
        </div>
        <el-skeleton v-if="loading" :rows="5" animated />
        <button
          v-for="task in tasks"
          v-else
          :key="task.id"
          class="task-card"
          :class="{ active: selectedTask?.id === task.id }"
          @click="selectTask(task)"
        >
          <span class="priority">{{ task.priority }}</span>
          <strong>{{ task.case_id }}</strong>
          <small>{{ task.status }} · {{ task.required_role }}</small>
        </button>
        <el-empty v-if="!loading && !tasks.length" description="暂无待审核任务" />
      </aside>

      <section v-if="packet" class="packet">
        <div class="packet-head">
          <div>
            <p class="eyebrow">REVIEW PACKET #{{ packet.packet_version }}</p>
            <h2>{{ packet.case_summary.title }}</h2>
          </div>
          <el-tag type="danger" effect="dark">
            {{ packet.case_summary.risk_level }}
          </el-tag>
        </div>

        <div class="risk-strip" v-if="packet.risk_flags?.length">
          <strong>风险重点</strong>
          <el-tag v-for="flag in packet.risk_flags" :key="flag" type="danger">
            {{ flag }}
          </el-tag>
        </div>

        <div class="grid">
          <article><h3>案件摘要</h3><pre>{{ packet.case_summary }}</pre></article>
          <article><h3>主张与争点</h3><pre>{{ packet.claims }}\n{{ packet.issues }}</pre></article>
          <article><h3>证据矩阵</h3><pre>{{ packet.evidence_matrix }}</pre></article>
          <article><h3>非最终裁决草案</h3><pre>{{ packet.draft }}</pre></article>
        </div>

        <article class="plan">
          <h3>待审批执行方案</h3>
          <pre>{{ packet.remedy }}</pre>
          <el-input
            v-model="modifiedPlan"
            type="textarea"
            :rows="8"
            aria-label="修改后的执行方案"
          />
        </article>

        <div class="decision">
          <el-input
            v-model="reason"
            type="textarea"
            :rows="3"
            placeholder="审核理由（必填）"
          />
          <div class="actions">
            <el-button :disabled="!canDecide" @click="decide('REQUEST_MORE_EVIDENCE')">要求补证</el-button>
            <el-button :disabled="!canDecide" @click="decide('ESCALATE_MANUAL')">转人工</el-button>
            <el-button :disabled="!canDecide" type="danger" @click="decide('REJECT')">驳回</el-button>
            <el-button :disabled="!canDecide" type="warning" @click="decide('MODIFY_AND_APPROVE')">修改并批准</el-button>
            <el-button :disabled="!canDecide" type="primary" @click="decide('APPROVE')">确认批准执行</el-button>
          </div>
        </div>
      </section>
      <el-empty v-else class="packet empty" description="选择任务查看完整审核包" />
    </section>
  </main>
</template>
