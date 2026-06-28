<script setup>
import { computed } from "vue";
import { useRoute } from "vue-router";
import { actor, roleLabels } from "./state/actor";

const route = useRoute();
const showReview = computed(() =>
  ["CUSTOMER_SERVICE", "PLATFORM_REVIEWER", "ADMIN"].includes(actor.role),
);
</script>

<template>
  <div class="app-shell">
    <aside class="side-nav">
      <router-link class="brand" to="/cases">
        <span class="brand-mark">衡</span>
        <span>
          <strong>履约争议裁决</strong>
          <small>Human-gated operations</small>
        </span>
      </router-link>

      <nav aria-label="主导航">
        <router-link to="/cases">
          <span>案件中心</span>
          <small>Case workspace</small>
        </router-link>
        <router-link v-if="showReview" to="/review">
          <span>平台审核台</span>
          <small>Review queue</small>
        </router-link>
      </nav>

      <div class="scope-note">
        <strong>责任边界</strong>
        <p>前端仅展示后端状态并提交操作，不生成裁决或审批规则。</p>
      </div>
    </aside>

    <div class="main-column">
      <header class="topbar">
        <div>
          <p class="eyebrow">ORDER FULFILLMENT DISPUTE SYSTEM</p>
          <h1>{{ route.meta.title || "案件工作台" }}</h1>
        </div>
        <div class="actor-switcher" aria-label="当前访问身份">
          <el-input v-model="actor.id" aria-label="身份 ID" />
          <el-select v-model="actor.role" aria-label="角色">
            <el-option
              v-for="(label, role) in roleLabels"
              :key="role"
              :label="label"
              :value="role"
            />
          </el-select>
        </div>
      </header>

      <main class="page">
        <router-view :key="`${route.fullPath}:${actor.id}:${actor.role}`" />
      </main>
    </div>
  </div>
</template>
