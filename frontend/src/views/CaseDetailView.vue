<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import { caseApi } from "../api/cases";
import { optional } from "../api/client";
import JsonPanel from "../components/JsonPanel.vue";
import { actor } from "../state/actor";
import { dateTime, percent, statusType } from "../utils/format";

const route = useRoute();
const caseId = computed(() => route.params.caseId);
const loading = ref(true);
const activeTab = ref("overview");
const disputeCase = ref(null);
const dossier = ref(null);
const hearing = ref(null);
const draft = ref(null);
const remedy = ref(null);
const actions = ref([]);
const auditLogs = ref([]);
const evaluation = ref(null);
const uploadFile = ref(null);
const actorSourceType =
  actor.role === "USER"
    ? "USER_UPLOAD"
    : actor.role === "MERCHANT"
      ? "MERCHANT_UPLOAD"
      : "PLATFORM_UPLOAD";
const upload = reactive({
  evidenceType: "PARTY_STATEMENT",
  sourceType: actorSourceType,
  visibility: "PARTIES",
});
const isStaff = computed(() =>
  ["CUSTOMER_SERVICE", "PLATFORM_REVIEWER", "ADMIN"].includes(actor.role),
);
const partyRoute = computed(() => {
  if (actor.role === "USER") return "user";
  if (actor.role === "MERCHANT") return "merchant";
  return null;
});

async function load() {
  loading.value = true;
  try {
    disputeCase.value = await caseApi.get(actor, caseId.value);
    [
      dossier.value,
      hearing.value,
      draft.value,
      remedy.value,
      actions.value,
      evaluation.value,
    ] = await Promise.all([
      optional(caseApi.dossier(actor, caseId.value)),
      optional(caseApi.hearing(actor, caseId.value)),
      optional(caseApi.draft(actor, caseId.value)),
      optional(caseApi.remedy(actor, caseId.value)),
      optional(caseApi.actions(actor, caseId.value), []),
      disputeCase.value.case_status === "CLOSED" && actor.role === "ADMIN"
        ? optional(caseApi.evaluation(actor, caseId.value))
        : null,
    ]);
    auditLogs.value = isStaff.value
      ? await optional(caseApi.auditLogs(actor, caseId.value), [])
      : [];
  } catch (error) {
    ElMessage.error(error.message);
  } finally {
    loading.value = false;
  }
}

function selectUpload(file) {
  uploadFile.value = file.raw;
}

async function uploadEvidence() {
  if (!uploadFile.value) {
    ElMessage.warning("请先选择文件");
    return;
  }
  try {
    await caseApi.uploadEvidence(actor, caseId.value, uploadFile.value, upload);
    ElMessage.success("证据已上传");
    uploadFile.value = null;
    await load();
    activeTab.value = "evidence";
  } catch (error) {
    ElMessage.error(error.message);
  }
}

onMounted(load);
</script>

<template>
  <section v-loading="loading" class="stack">
    <template v-if="disputeCase">
      <div class="case-hero">
        <div>
          <router-link class="back-link" to="/cases">← 返回案件列表</router-link>
          <p class="eyebrow">{{ disputeCase.id }}</p>
          <h2>{{ disputeCase.title }}</h2>
          <p>{{ disputeCase.description }}</p>
        </div>
        <div class="status-cluster">
          <el-tag :type="statusType(disputeCase.risk_level)" effect="dark">
            {{ disputeCase.risk_level }} 风险
          </el-tag>
          <el-tag :type="statusType(disputeCase.case_status)">
            {{ disputeCase.case_status }}
          </el-tag>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="detail-tabs">
        <el-tab-pane label="概览" name="overview">
          <div class="metric-grid">
            <div><small>案件类型</small><strong>{{ disputeCase.case_type }}</strong></div>
            <div><small>路由</small><strong>{{ disputeCase.route_type || "待路由" }}</strong></div>
            <div><small>订单号</small><strong>{{ disputeCase.order_id || "—" }}</strong></div>
            <div><small>更新时间</small><strong>{{ dateTime(disputeCase.updated_at) }}</strong></div>
          </div>
          <div class="two-column">
            <JsonPanel title="Intake 缺失信息" :value="disputeCase.missing_slots" />
            <JsonPanel title="当前补证请求" :value="hearing?.pending_requests_json" />
          </div>
          <div v-if="partyRoute" class="callout">
            <div>
              <strong>当事人补证入口</strong>
              <p>查看补证请求、上传材料并提交说明。</p>
            </div>
            <router-link
              class="el-button el-button--primary"
              :to="`/cases/${caseId}/submissions/${partyRoute}`"
            >
              进入补证页
            </router-link>
          </div>
        </el-tab-pane>

        <el-tab-pane label="证据卷宗" name="evidence">
          <div class="section-head compact">
            <div>
              <h3>Evidence Dossier v{{ dossier?.version || "—" }}</h3>
              <p>原始文件不会被覆盖；解析、来源和脱敏状态以后端为准。</p>
            </div>
          </div>
          <div class="upload-box">
            <el-select v-model="upload.evidenceType" aria-label="证据类型">
              <el-option label="当事人陈述" value="PARTY_STATEMENT" />
              <el-option label="物流凭证" value="LOGISTICS_PROOF" />
              <el-option label="商品图片" value="PRODUCT_IMAGE" />
              <el-option label="聊天记录" value="CHAT_RECORD" />
              <el-option label="其他材料" value="OTHER" />
            </el-select>
            <el-select v-model="upload.sourceType" disabled aria-label="证据来源">
              <el-option label="用户上传" value="USER_UPLOAD" />
              <el-option label="商家上传" value="MERCHANT_UPLOAD" />
              <el-option label="平台上传" value="PLATFORM_UPLOAD" />
            </el-select>
            <el-upload
              :auto-upload="false"
              :limit="1"
              accept=".png,.jpg,.jpeg,.pdf,.doc,.docx,.xls,.xlsx"
              :on-change="selectUpload"
            >
              <el-button>选择证据</el-button>
            </el-upload>
            <el-button type="primary" @click="uploadEvidence">上传</el-button>
          </div>
          <el-table :data="dossier?.evidences || []" row-key="id">
            <el-table-column prop="original_filename" label="文件" min-width="220" />
            <el-table-column prop="evidence_type" label="类型" width="170" />
            <el-table-column prop="source_type" label="来源" width="110" />
            <el-table-column prop="parse_status" label="解析状态" width="130" />
            <el-table-column label="脱敏" width="90">
              <template #default="{ row }">
                {{ row.desensitized ? "已脱敏" : "原始" }}
              </template>
            </el-table-column>
            <el-table-column prop="visibility" label="可见范围" width="120" />
          </el-table>
          <JsonPanel title="主张－争点－证据矩阵" :value="dossier?.matrix" />
        </el-tab-pane>

        <el-tab-pane label="事件时间线" name="timeline">
          <el-timeline v-if="dossier?.timeline?.length">
            <el-timeline-item
              v-for="event in dossier.timeline"
              :key="event.evidence_id"
              :timestamp="dateTime(event.occurred_at)"
              placement="top"
            >
              <strong>{{ event.evidence_type }}</strong>
              <p>{{ event.description }} · {{ event.source_type }}</p>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无订单、物流、售后或证据事件" />
        </el-tab-pane>

        <el-tab-pane label="裁决草案" name="draft">
          <div class="draft-banner">
            <span>非最终裁决</span>
            <strong>{{ draft?.recommended_decision || "尚未生成" }}</strong>
            <span>置信度 {{ percent(draft?.confidence) }}</span>
          </div>
          <JsonPanel title="事实认定" :value="draft?.fact_findings" />
          <div class="two-column">
            <JsonPanel title="证据评价" :value="draft?.evidence_assessment" />
            <JsonPanel title="规则适用" :value="draft?.policy_application" />
          </div>
          <JsonPanel title="草案正文" :value="draft?.draft_text" />
          <JsonPanel title="审核重点" :value="draft?.reviewer_attention" />
        </el-tab-pane>

        <el-tab-pane label="执行方案" name="remedy">
          <JsonPanel title="Remedy Plan" :value="remedy" />
        </el-tab-pane>

        <el-tab-pane label="执行记录" name="actions">
          <el-table :data="actions" row-key="action_record_id">
            <el-table-column prop="action_type" label="动作" width="190" />
            <el-table-column prop="execution_status" label="状态" width="130">
              <template #default="{ row }">
                <el-tag :type="statusType(row.execution_status)">
                  {{ row.execution_status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="attempt_count" label="尝试次数" width="100" />
            <el-table-column prop="approved_by" label="批准人" width="150" />
            <el-table-column prop="executed_by" label="执行者" width="150" />
            <el-table-column label="执行时间" min-width="170">
              <template #default="{ row }">{{ dateTime(row.execution_time) }}</template>
            </el-table-column>
            <el-table-column prop="error_message" label="错误" min-width="180" />
          </el-table>
          <el-empty v-if="!actions.length" description="暂无执行记录" />
        </el-tab-pane>

        <el-tab-pane v-if="isStaff" label="审计日志" name="audit">
          <el-timeline v-if="auditLogs.length">
            <el-timeline-item
              v-for="entry in auditLogs"
              :key="entry.id"
              :timestamp="dateTime(entry.created_at)"
            >
              <strong>{{ entry.action }}</strong>
              <p>{{ entry.role }} · {{ entry.actor_id }} · {{ entry.outcome }}</p>
              <JsonPanel title="变更后" :value="entry.after" />
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else description="暂无可展示的审计记录" />
        </el-tab-pane>

        <el-tab-pane
          v-if="disputeCase.case_status === 'CLOSED' && actor.role === 'ADMIN'"
          label="离线评估"
          name="evaluation"
        >
          <JsonPanel title="Evaluation Report" :value="evaluation" />
        </el-tab-pane>
      </el-tabs>
    </template>
  </section>
</template>
