<!--
  文件作用：前端页面视图文件，组织售后争议对应页面的数据加载、交互和展示。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed, onMounted, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { reviewApi } from "../api/review";
import JsonPanel from "../components/JsonPanel.vue";
import { actor } from "../state/actor";
import { statusType } from "../utils/format";

const tasks = ref([]);
const selectedTask = ref(null);
const packet = ref(null);
const loading = ref(false);
const reason = ref("");
const modifiedPlan = ref("{}");
const canDecide = computed(() =>
  ["PLATFORM_REVIEWER", "ADMIN"].includes(actor.role),
);

// 业务位置：【前端案件页面】loadTasks：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function loadTasks() {
  loading.value = true;
  try {
    tasks.value = await reviewApi.list(actor);
  } catch (error) {
    ElMessage.error(error.message);
  } finally {
    loading.value = false;
  }
}

// 业务位置：【前端案件页面】selectTask：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function selectTask(task) {
  try {
    selectedTask.value = task;
    packet.value = await reviewApi.packet(actor, task.id);
    modifiedPlan.value = JSON.stringify(packet.value.remedy, null, 2);
  } catch (error) {
    ElMessage.error(error.message);
  }
}

// 业务位置：【前端案件页面】decide：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
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
  try {
    await ElMessageBox.confirm(
      `确认提交 ${decision}？该操作将写入不可绕过的审核审计。`,
      "二次确认",
      { type: "warning", confirmButtonText: "确认提交" },
    );
    await reviewApi.decide(actor, selectedTask.value.id, {
      decision,
      reason: reason.value,
      approved_plan: approvedPlan,
    });
    ElMessage.success("审核决定已提交");
    packet.value = null;
    selectedTask.value = null;
    reason.value = "";
    await loadTasks();
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(error.message);
  }
}

onMounted(loadTasks);
</script>

<template>
  <section class="review-layout">
    <aside class="review-queue">
      <div class="panel-title">
        <div><p class="eyebrow">QUEUE</p><h2>待审核任务</h2></div>
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

    <div v-if="packet" class="review-packet">
      <div class="section-head">
        <div>
          <p class="eyebrow">REVIEW PACKET #{{ packet.packet_version }}</p>
          <h2>{{ packet.case_summary.title }}</h2>
          <p>{{ packet.case_id }}</p>
        </div>
        <el-tag :type="statusType(packet.case_summary.risk_level)" effect="dark">
          {{ packet.case_summary.risk_level }}
        </el-tag>
      </div>

      <div v-if="packet.risk_flags?.length" class="risk-strip">
        <strong>高风险提示</strong>
        <el-tag v-for="flag in packet.risk_flags" :key="flag" type="danger">
          {{ flag }}
        </el-tag>
      </div>

      <div class="two-column">
        <JsonPanel title="案件摘要" :value="packet.case_summary" />
        <JsonPanel title="主张与争点" :value="{ claims: packet.claims, issues: packet.issues }" />
        <JsonPanel title="证据矩阵" :value="packet.evidence_matrix" />
        <JsonPanel title="非最终裁决草案" :value="packet.draft" />
      </div>
      <JsonPanel title="规则适用" :value="packet.draft?.policy_application" />
      <JsonPanel title="审核重点" :value="packet.draft?.reviewer_attention" />

      <article class="form-card remedy-editor">
        <h3>待审批执行方案</h3>
        <JsonPanel title="原始方案" :value="packet.remedy" />
        <el-input
          v-model="modifiedPlan"
          type="textarea"
          :rows="9"
          aria-label="修改后的执行方案"
        />
      </article>

      <div class="decision-panel">
        <el-input
          v-model="reason"
          type="textarea"
          :rows="3"
          maxlength="2000"
          show-word-limit
          placeholder="审核理由（必填）"
        />
        <div class="actions">
          <el-button :disabled="!canDecide" @click="decide('REQUEST_MORE_EVIDENCE')">
            要求补证
          </el-button>
          <el-button :disabled="!canDecide" @click="decide('ESCALATE_MANUAL')">
            转人工
          </el-button>
          <el-button :disabled="!canDecide" type="danger" @click="decide('REJECT')">
            驳回
          </el-button>
          <el-button
            :disabled="!canDecide"
            type="warning"
            @click="decide('MODIFY_AND_APPROVE')"
          >
            修改并批准
          </el-button>
          <el-button :disabled="!canDecide" type="primary" @click="decide('APPROVE')">
            确认批准执行
          </el-button>
        </div>
      </div>
    </div>
    <el-empty v-else class="review-packet" description="选择任务查看完整审核包" />
  </section>
</template>
