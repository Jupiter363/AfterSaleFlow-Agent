<!--
  文件作用：前端页面视图文件，组织售后争议对应页面的数据加载、交互和展示。
  说明：本注释用于帮助读者先了解组件/页面职责，再阅读 template、script 和 style。
-->

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { caseApi } from "../api/cases";
import JsonPanel from "../components/JsonPanel.vue";
import { actor } from "../state/actor";

const props = defineProps({
  party: { type: String, required: true },
});
const route = useRoute();
const router = useRouter();
const caseId = computed(() => route.params.caseId);
const hearing = ref(null);
const disputeCase = ref(null);
const uploaded = ref([]);
const file = ref(null);
const submitting = ref(false);
const form = reactive({
  submission_text: "",
  evidence_ids: [],
  evidenceType: "PARTY_STATEMENT",
  visibility: "PARTIES",
});
const partyLabel = computed(() => (props.party === "user" ? "用户" : "商家"));
const expectedRole = computed(() =>
  props.party === "user" ? "USER" : "MERCHANT",
);
const canSubmit = computed(() => actor.role === expectedRole.value);
const requests = computed(() => {
  if (!hearing.value?.pending_requests_json) return [];
  try {
    return JSON.parse(hearing.value.pending_requests_json);
  } catch {
    return [hearing.value.pending_requests_json];
  }
});

// 业务位置：【前端案件页面】load：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function load() {
  try {
    [disputeCase.value, hearing.value] = await Promise.all([
      caseApi.get(actor, caseId.value),
      caseApi.hearing(actor, caseId.value),
    ]);
  } catch (error) {
    ElMessage.error(error.message);
  }
}

// 业务位置：【前端案件页面】chooseFile：围绕 当前阶段业务数据 计算本模块需要的派生信息，使其能够从 路由参数、API 数据和状态仓库 正确进入 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
function chooseFile(uploadFile) {
  file.value = uploadFile.raw;
}

// 业务位置：【前端案件页面】upload：读取 当前阶段业务数据，并依据当前案件、角色和会话权限裁剪成可用输入。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function upload() {
  if (!file.value) {
    ElMessage.warning("请先选择证据文件");
    return;
  }
  try {
    const evidence = await caseApi.uploadEvidence(actor, caseId.value, file.value, {
      evidenceType: form.evidenceType,
      sourceType: `${props.party.toUpperCase()}_UPLOAD`,
      visibility: form.visibility,
    });
    uploaded.value.push(evidence);
    form.evidence_ids.push(evidence.id);
    file.value = null;
    ElMessage.success("证据已上传并进入解析队列");
  } catch (error) {
    ElMessage.error(error.message);
  }
}

// 业务位置：【前端案件页面】submit：执行 当前阶段业务数据 对应的业务动作，并将结果交给 用户可操作的案件视图。上游：路由参数、API 数据和状态仓库。下游：用户可操作的案件视图。边界：页面状态不得绕过后端权限。
async function submit() {
  if (!canSubmit.value) {
    ElMessage.error(`请切换为${partyLabel.value}身份后提交`);
    return;
  }
  if (!form.submission_text.trim() && !form.evidence_ids.length) {
    ElMessage.warning("请填写说明或上传至少一份证据");
    return;
  }
  submitting.value = true;
  try {
    await caseApi.submitEvidence(actor, caseId.value, props.party, {
      submission_text: form.submission_text,
      evidence_ids: form.evidence_ids,
    });
    ElMessage.success(`${partyLabel.value}补证已提交，流程将由后端恢复`);
    router.push(`/cases/${caseId.value}`);
  } catch (error) {
    ElMessage.error(error.message);
  } finally {
    submitting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="stack narrow">
    <div class="section-head">
      <div>
        <p class="eyebrow">{{ party.toUpperCase() }} EVIDENCE SUBMISSION</p>
        <h2>{{ partyLabel }}补证</h2>
        <p>{{ caseId }} · {{ disputeCase?.title }}</p>
      </div>
      <el-tag :type="canSubmit ? 'success' : 'danger'">
        {{ canSubmit ? "身份匹配" : `需 ${expectedRole} 身份` }}
      </el-tag>
    </div>

    <article class="request-card">
      <h3>待补材料</h3>
      <JsonPanel title="补证请求" :value="requests" empty="暂无结构化补证请求" />
      <p v-if="hearing?.waiting_until">
        截止时间：{{ hearing.waiting_until }}
      </p>
    </article>

    <article class="form-card">
      <h3>上传证据材料</h3>
      <p class="muted">支持图片、PDF、Word、Excel；原始文件由后端保存并异步解析。</p>
      <div class="upload-row">
        <el-select v-model="form.evidenceType" aria-label="证据类型">
          <el-option label="当事人陈述" value="PARTY_STATEMENT" />
          <el-option label="物流凭证" value="LOGISTICS_PROOF" />
          <el-option label="商品图片" value="PRODUCT_IMAGE" />
          <el-option label="聊天记录" value="CHAT_RECORD" />
          <el-option label="其他材料" value="OTHER" />
        </el-select>
        <el-upload
          :auto-upload="false"
          :limit="1"
          accept=".png,.jpg,.jpeg,.pdf,.doc,.docx,.xls,.xlsx"
          :on-change="chooseFile"
        >
          <el-button>选择文件</el-button>
        </el-upload>
        <el-button type="primary" plain @click="upload">上传材料</el-button>
      </div>
      <el-tag v-for="item in uploaded" :key="item.id" class="evidence-chip">
        {{ item.original_filename }} · {{ item.id }}
      </el-tag>
    </article>

    <article class="form-card">
      <h3>补充说明</h3>
      <el-input
        v-model="form.submission_text"
        type="textarea"
        :rows="7"
        maxlength="20000"
        show-word-limit
        placeholder="请陈述事实并指出材料与争点的关系"
      />
      <div class="form-actions">
        <el-button @click="router.back()">返回</el-button>
        <el-button
          type="primary"
          :disabled="!canSubmit"
          :loading="submitting"
          @click="submit"
        >
          提交补证
        </el-button>
      </div>
    </article>
  </section>
</template>
