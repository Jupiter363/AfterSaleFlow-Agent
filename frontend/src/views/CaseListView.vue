<script setup>
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import { caseApi } from "../api/cases";
import { actor } from "../state/actor";
import { dateTime, statusType } from "../utils/format";

const loading = ref(false);
const result = ref({ items: [], page: 0, size: 20, total_elements: 0 });
const filters = reactive({ status: "", case_type: "", page: 0, size: 20 });

const statuses = [
  "INTAKE_COMPLETED",
  "DOSSIER_BUILT",
  "WAITING_PARTY_SUBMISSION",
  "WAITING_HUMAN_REVIEW",
  "APPROVED_FOR_EXECUTION",
  "EXECUTING",
  "CLOSED",
];
const caseTypes = ["REGULAR_FULFILLMENT", "RULE_BASED", "DISPUTE"];

async function load() {
  loading.value = true;
  try {
    result.value = await caseApi.list(actor, filters);
  } catch (error) {
    ElMessage.error(error.message);
  } finally {
    loading.value = false;
  }
}

function changePage(page) {
  filters.page = page - 1;
  load();
}

onMounted(load);
</script>

<template>
  <section class="stack">
    <div class="section-head">
      <div>
        <p class="eyebrow">ROLE-SCOPED CASE INDEX</p>
        <h2>可访问案件</h2>
        <p>列表范围由后端依据当前身份过滤。</p>
      </div>
      <el-button type="primary" @click="load">刷新列表</el-button>
    </div>

    <div class="filter-bar">
      <el-select v-model="filters.status" clearable placeholder="全部状态">
        <el-option
          v-for="status in statuses"
          :key="status"
          :label="status"
          :value="status"
        />
      </el-select>
      <el-select v-model="filters.case_type" clearable placeholder="全部类型">
        <el-option
          v-for="type in caseTypes"
          :key="type"
          :label="type"
          :value="type"
        />
      </el-select>
      <el-button @click="filters.page = 0; load()">应用筛选</el-button>
    </div>

    <el-table v-loading="loading" :data="result.items" row-key="id">
      <el-table-column prop="id" label="Case ID" min-width="210">
        <template #default="{ row }">
          <router-link class="case-link" :to="`/cases/${row.id}`">
            {{ row.id }}
          </router-link>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="案件标题" min-width="220" />
      <el-table-column prop="case_type" label="类型" width="170" />
      <el-table-column prop="case_status" label="状态" width="190">
        <template #default="{ row }">
          <el-tag :type="statusType(row.case_status)">
            {{ row.case_status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="risk_level" label="风险" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.risk_level)">
            {{ row.risk_level }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="180">
        <template #default="{ row }">{{ dateTime(row.updated_at) }}</template>
      </el-table-column>
    </el-table>

    <el-empty
      v-if="!loading && !result.items.length"
      description="当前身份下暂无匹配案件"
    />
    <el-pagination
      v-if="result.total_elements > result.size"
      background
      layout="prev, pager, next, total"
      :page-size="result.size"
      :total="result.total_elements"
      :current-page="result.page + 1"
      @current-change="changePage"
    />
  </section>
</template>
