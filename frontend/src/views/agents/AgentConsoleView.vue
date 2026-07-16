<!--
  文件作用：为平台审核员提供数字人运行看板、Prompt、策略、调试和版本治理界面。
  边界：当前页面使用稳定样例数据承载交互设计，真实发布仍需接入受权限保护的配置管理 API。
-->

<script setup>
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import {
  consoleTabs,
  createAgentConsoleAgents,
  digitalHumanAvatarOptions,
  promptSafetyRules,
} from "./agentConsoleFixtures";

const agents = ref(createAgentConsoleAgents());
const activeAgentId = ref(agents.value[0].id);
const activeTab = ref("info");
const activeRange = ref("7d");
const activePromptId = ref(agents.value[0].prompts[0].id);
const searchQuery = ref("");
const mobileAgentListOpen = ref(false);
const dirtyAgentIds = ref(new Set());
const publishDialogOpen = ref(false);
const publishNote = ref("");
const avatarDialogOpen = ref(false);
const pendingAvatar = ref("");
const uploadedAvatarName = ref("");
const debugActorRole = ref("用户");
const debugInput = ref("商品显示已签收，但我没有收到。商家说需要我先提供签收异常证明。请问下一步要做什么？");
const debugResult = ref(null);
const detailDialogKind = ref("");
const detailVersion = ref(null);

const promptSnapshots = new Map();
for (const agent of agents.value) {
  for (const prompt of agent.prompts) {
    promptSnapshots.set(`${agent.id}:${prompt.id}`, prompt.content);
  }
}

const rangeOptions = [
  { id: "7d", label: "近 7 天" },
  { id: "30d", label: "近 30 天" },
  { id: "90d", label: "近 90 天" },
];

const filteredAgents = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase();
  if (!keyword) return agents.value;
  return agents.value.filter((agent) =>
    [agent.name, agent.displayName, agent.englishName, agent.stage]
      .join(" ")
      .toLowerCase()
      .includes(keyword),
  );
});

const activeAgent = computed(
  () => agents.value.find((agent) => agent.id === activeAgentId.value) || agents.value[0],
);

const activePrompt = computed(
  () =>
    activeAgent.value.prompts.find((prompt) => prompt.id === activePromptId.value) ||
    activeAgent.value.prompts[0],
);

const activeMetrics = computed(
  () => activeAgent.value.metricsByRange[activeRange.value],
);

const activeAgentDirty = computed(() => dirtyAgentIds.value.has(activeAgent.value.id));
const healthyAgentCount = computed(
  () => agents.value.filter((agent) => agent.enabled && agent.status === "healthy").length,
);
const enabledAgentCount = computed(() => agents.value.filter((agent) => agent.enabled).length);
const promptCharacterCount = computed(() => activePrompt.value.content.length);
const avatarSelectionChanged = computed(
  () => Boolean(pendingAvatar.value) && pendingAvatar.value !== activeAgent.value.avatar,
);
const detailDialogMeta = computed(() => {
  const metadata = {
    runs: { eyebrow: "RUN AUDIT", title: "全部异常与转人工记录" },
    prompt: { eyebrow: "PROMPT EDITOR", title: `${activePrompt.value.name} · 展开编辑` },
    variables: { eyebrow: "CONTEXT VARIABLES", title: "全部可用上下文变量" },
    safety: { eyebrow: "LOCKED SAFETY", title: "不可覆盖的安全边界" },
    profile: { eyebrow: "AGENT PROFILE", title: "Profile 权限包络" },
    debug: { eyebrow: "SANDBOX RESULT", title: "完整调试结果" },
    version: { eyebrow: "VERSION DIFF", title: `${detailVersion.value?.version || "配置版本"} 变更详情` },
  };
  return metadata[detailDialogKind.value] || { eyebrow: "DETAIL", title: "配置详情" };
});

const capabilityGroups = computed(() => {
  const groups = {};
  for (const capability of activeAgent.value.capabilities) {
    groups[capability.group] ||= [];
    groups[capability.group].push(capability);
  }
  return Object.entries(groups);
});

function selectAgent(agentId) {
  activeAgentId.value = agentId;
  activePromptId.value = activeAgent.value.prompts[0].id;
  debugResult.value = null;
  mobileAgentListOpen.value = false;
  avatarDialogOpen.value = false;
  closeDetailDialog();
}

function setActiveTab(tabId) {
  activeTab.value = tabId;
}

function markDirty() {
  const next = new Set(dirtyAgentIds.value);
  next.add(activeAgent.value.id);
  dirtyAgentIds.value = next;
}

function clearDirty(agentId) {
  const next = new Set(dirtyAgentIds.value);
  next.delete(agentId);
  dirtyAgentIds.value = next;
}

function toggleAgentEnabled() {
  activeAgent.value.enabled = !activeAgent.value.enabled;
  markDirty();
}

function toggleCapability(capability) {
  if (capability.locked) {
    ElMessage.info("该项属于锁定的安全策略，不能在角色配置中关闭");
    return;
  }
  capability.enabled = !capability.enabled;
  markDirty();
}

function setCapabilityMode(capability, mode) {
  capability.mode = mode;
  capability.enabled = mode !== "off";
  markDirty();
}

function openAvatarDialog() {
  pendingAvatar.value = activeAgent.value.avatar;
  uploadedAvatarName.value = "";
  avatarDialogOpen.value = true;
}

function closeAvatarDialog() {
  avatarDialogOpen.value = false;
  pendingAvatar.value = "";
  uploadedAvatarName.value = "";
}

function openDetailDialog(kind, payload = null) {
  detailDialogKind.value = kind;
  detailVersion.value = payload;
}

function closeDetailDialog() {
  detailDialogKind.value = "";
  detailVersion.value = null;
}

function selectAvatar(avatar) {
  pendingAvatar.value = avatar.src;
  uploadedAvatarName.value = "";
}

function uploadAvatar(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) return;
  if (!file.type.startsWith("image/")) {
    ElMessage.warning("请选择 PNG、JPG 或 WebP 图片");
    return;
  }
  if (file.size > 2 * 1024 * 1024) {
    ElMessage.warning("数字人形象图片不能超过 2MB");
    return;
  }
  const reader = new FileReader();
  reader.onload = () => {
    pendingAvatar.value = String(reader.result || "");
    uploadedAvatarName.value = file.name;
  };
  reader.onerror = () => ElMessage.error("图片读取失败，请重新选择");
  reader.readAsDataURL(file);
}

function confirmAvatarChange() {
  if (!avatarSelectionChanged.value) return;
  activeAgent.value.avatar = pendingAvatar.value;
  markDirty();
  closeAvatarDialog();
  ElMessage.success("数字人形象已更新到配置草稿");
}

function selectPrompt(promptId) {
  activePromptId.value = promptId;
}

function restorePrompt() {
  const key = `${activeAgent.value.id}:${activePrompt.value.id}`;
  activePrompt.value.content = promptSnapshots.get(key) || "";
  clearDirty(activeAgent.value.id);
  ElMessage.info("已恢复到最近一次保存的草稿");
}

function nextVersion(version) {
  if (!/v\d+$/.test(version)) return `${version}-v2`;
  return version.replace(/v(\d+)$/, (_, number) => `v${Number(number) + 1}`);
}

function saveDraft() {
  const agent = activeAgent.value;
  if (!activeAgentDirty.value && agent.hasDraft) {
    ElMessage.info("当前配置已经是最新草稿");
    return;
  }

  if (!agent.hasDraft) {
    agent.draftVersion = nextVersion(agent.publishedVersion);
    agent.versions.unshift({
      version: agent.draftVersion,
      status: "draft",
      statusLabel: "草稿",
      operator: "当前审核员",
      time: "刚刚",
      summary: "基于当前生产版本创建配置草稿。",
    });
  } else {
    const draft = agent.versions.find((version) => version.status === "draft");
    if (draft) {
      draft.time = "刚刚";
      draft.operator = "当前审核员";
    }
  }

  agent.hasDraft = true;
  promptSnapshots.set(`${agent.id}:${activePrompt.value.id}`, activePrompt.value.content);
  clearDirty(agent.id);
  ElMessage.success("原型草稿已更新，尚未写入配置服务");
}

function openPublishDialog() {
  if (activeAgentDirty.value) {
    ElMessage.warning("请先保存当前修改，再发布草稿");
    return;
  }
  if (!activeAgent.value.hasDraft) {
    ElMessage.info("当前没有可发布的草稿");
    return;
  }
  publishNote.value = "";
  publishDialogOpen.value = true;
}

function closePublishDialog() {
  publishDialogOpen.value = false;
  publishNote.value = "";
}

function confirmPublish() {
  if (!publishNote.value.trim()) return;
  const agent = activeAgent.value;
  for (const version of agent.versions) {
    if (version.status === "published") {
      version.status = "archived";
      version.statusLabel = "历史版本";
    }
  }
  const draft = agent.versions.find((version) => version.status === "draft");
  if (draft) {
    draft.status = "published";
    draft.statusLabel = "生产中";
    draft.time = "刚刚";
    draft.summary = publishNote.value.trim();
  }
  agent.publishedVersion = agent.draftVersion;
  agent.lastPublishedAt = "刚刚";
  agent.hasDraft = false;
  closePublishDialog();
  ElMessage.success(`已模拟发布 ${agent.name} 配置，未影响运行服务`);
}

function runDebug() {
  if (!debugInput.value.trim()) {
    ElMessage.warning("请输入用于调试的模拟对话");
    return;
  }
  debugResult.value = {
    response: activeAgent.value.testResponse,
    latency: activeAgent.value.id === "presiding_judge" ? "4.3 秒" : "1.8 秒",
    tokens: activeAgent.value.id === "evidence_clerk" ? "2,846" : "1,328",
    schema: "通过",
    handoff: activeAgent.value.status === "attention" ? "建议关注" : "未触发",
    guardrails: ["角色权限校验通过", "不可信输入隔离通过", "最终决定边界通过"],
  };
}

function createRollbackDraft(version) {
  const agent = activeAgent.value;
  agent.draftVersion = nextVersion(agent.publishedVersion);
  agent.hasDraft = true;
  agent.versions = agent.versions.filter((item) => item.status !== "draft");
  agent.versions.unshift({
    version: agent.draftVersion,
    status: "draft",
    statusLabel: "草稿",
    operator: "当前审核员",
    time: "刚刚",
    summary: `基于 ${version.version} 创建回滚草稿，尚未影响生产。`,
  });
  ElMessage.success(`已基于 ${version.version} 创建新草稿`);
}
</script>

<template>
  <section class="agent-console" data-agent-console>
    <header class="agent-console__header">
      <div class="agent-console__title">
        <span>AI AGENT OPERATIONS · 交互原型</span>
        <h1>数字人管理中心</h1>
        <p class="agent-console__description">
          统一管理数字人的运行质量、回复表达、Prompt、能力边界与发布版本。当前为脱敏示例数据，尚未接入配置服务。
        </p>
      </div>

      <div class="agent-console__header-actions">
        <div class="fleet-status" aria-label="数字人集群状态">
          <span><i aria-hidden="true"></i>{{ enabledAgentCount }}/{{ agents.length }} 已启用</span>
          <small>示例状态 · {{ healthyAgentCount }} 个正常 · 1 个需关注</small>
        </div>
        <button
          type="button"
          class="action-button action-button--secondary"
          data-save-draft
          @click="saveDraft"
        >
          保存草稿
        </button>
        <button
          type="button"
          class="action-button action-button--primary"
          :disabled="activeAgentDirty || !activeAgent.hasDraft"
          data-publish-config
          @click="openPublishDialog"
        >
          发布配置
        </button>
      </div>
    </header>

    <div class="agent-manager">
      <aside class="agent-sidebar" aria-label="数字人列表">
        <div class="agent-sidebar__heading">
          <div>
            <span>数字人</span>
            <strong>{{ agents.length }}</strong>
          </div>
          <small>配置预览</small>
          <button
            type="button"
            class="mobile-agent-toggle"
            :aria-expanded="mobileAgentListOpen"
            aria-controls="agent-mobile-picker"
            @click="mobileAgentListOpen = !mobileAgentListOpen"
          >
            {{ mobileAgentListOpen ? "收起列表" : `切换数字人 · ${activeAgent.name}` }}
          </button>
        </div>

        <div
          id="agent-mobile-picker"
          class="agent-picker"
          :class="{ 'agent-picker--open': mobileAgentListOpen }"
        >
          <label class="agent-search">
            <span>搜索数字人</span>
            <input v-model="searchQuery" type="search" placeholder="名称或处理阶段" />
          </label>

          <nav class="agent-list" aria-label="选择数字人">
            <button
              v-for="agent in filteredAgents"
              :key="agent.id"
              type="button"
              class="agent-list-item"
              :class="{ 'agent-list-item--active': agent.id === activeAgent.id }"
              :aria-current="agent.id === activeAgent.id ? 'page' : undefined"
              :data-agent-id="agent.id"
              data-agent-role
              @click="selectAgent(agent.id)"
            >
              <span class="agent-list-item__avatar">
                <img :src="agent.avatar" :alt="`${agent.name}${agent.displayName}`" />
                <i
                  :class="`status-dot status-dot--${agent.enabled ? agent.status : 'offline'}`"
                  aria-hidden="true"
                ></i>
              </span>
              <span class="agent-list-item__copy">
                <strong>{{ agent.name }}</strong>
                <small>{{ agent.stage }}</small>
                <em>{{ agent.publishedVersion }}</em>
              </span>
              <span v-if="agent.hasDraft" class="draft-mark">草稿</span>
            </button>
          </nav>

          <p v-if="!filteredAgents.length" class="agent-sidebar__empty">没有匹配的数字人</p>
        </div>

        <div class="governance-note">
          <strong>统一治理已启用</strong>
          <span>4 条安全规则 · Profile 默认拒绝 · 人工终审保留</span>
        </div>
      </aside>

      <main class="agent-workbench">
        <nav class="agent-tabs" role="tablist" aria-label="数字人配置视图">
          <button
            v-for="tab in consoleTabs"
            :id="`agent-tab-${tab.id}`"
            :key="tab.id"
            type="button"
            role="tab"
            :aria-selected="activeTab === tab.id"
            :aria-controls="`agent-panel-${tab.id}`"
            :tabindex="activeTab === tab.id ? 0 : -1"
            :class="{ 'agent-tabs__item--active': activeTab === tab.id }"
            :data-agent-tab="tab.id"
            @click="setActiveTab(tab.id)"
          >
            {{ tab.label }}
          </button>
        </nav>

        <section
          v-if="activeTab === 'info'"
          id="agent-panel-info"
          class="agent-tab-panel agent-tab-panel--info"
          role="tabpanel"
          aria-labelledby="agent-tab-info"
          data-info-panel
        >
          <div class="panel-toolbar">
            <div>
              <h3>数字人信息</h3>
              <p>统一查看当前数字人的形象、服务定位、配置版本和治理状态。</p>
            </div>
            <span class="editing-state" :class="{ 'editing-state--dirty': activeAgentDirty }">
              {{ activeAgentDirty ? "有未保存修改" : activeAgent.hasDraft ? `${activeAgent.draftVersion} 草稿` : "与生产版本一致" }}
            </span>
          </div>

          <header class="agent-detail-header agent-info-profile-card" :data-agent-id="activeAgent.id" data-fixed-card>
            <div class="agent-detail-header__identity">
              <div class="agent-detail-header__portrait">
                <img :src="activeAgent.avatar" :alt="activeAgent.name" />
                <button
                  type="button"
                  class="avatar-change-button"
                  data-change-avatar
                  @click="openAvatarDialog"
                >
                  更换形象
                </button>
              </div>
              <div>
                <span>{{ activeAgent.englishName }}</span>
                <h2>{{ activeAgent.name }} <small>{{ activeAgent.displayName }}</small></h2>
                <p class="agent-detail__summary">{{ activeAgent.summary }}</p>
                <div class="agent-detail-header__tags">
                  <span>{{ activeAgent.stage }}</span>
                  <span v-for="audience in activeAgent.audiences" :key="audience">面向{{ audience }}</span>
                  <span :class="`health-label health-label--${activeAgent.status}`">
                    {{ activeAgent.enabled ? activeAgent.statusLabel : "已停用" }}
                  </span>
                </div>
              </div>
            </div>

            <div class="agent-detail-header__runtime">
              <div>
                <small>生产版本</small>
                <strong>{{ activeAgent.publishedVersion }}</strong>
                <span>发布于 {{ activeAgent.lastPublishedAt }}</span>
              </div>
              <button
                type="button"
                class="switch-control"
                role="switch"
                :aria-checked="activeAgent.enabled"
                :aria-label="`${activeAgent.enabled ? '停用' : '启用'}${activeAgent.name}`"
                data-agent-enabled-switch
                @click="toggleAgentEnabled"
              >
                <span aria-hidden="true"></span>
                {{ activeAgent.enabled ? "已启用" : "已停用" }}
              </button>
            </div>
          </header>

          <div class="agent-info-grid">
            <section class="agent-info-card" data-fixed-card>
              <header><div><span>SERVICE PROFILE</span><h3>服务定位</h3></div><small>{{ activeAgent.stage }}</small></header>
              <dl class="agent-info-list">
                <div><dt>服务阶段</dt><dd>{{ activeAgent.stage }}</dd></div>
                <div><dt>服务对象</dt><dd>{{ activeAgent.audiences.join("、") }}</dd></div>
                <div><dt>可运行状态</dt><dd>{{ activeAgent.allowedStates.join("、") }}</dd></div>
                <div><dt>上下文域</dt><dd>{{ activeAgent.contextScopes.length }} 个只读域</dd></div>
                <div><dt>输出 Schema</dt><dd><code>{{ activeAgent.outputSchema }}</code></dd></div>
              </dl>
            </section>

            <section class="agent-info-card" data-fixed-card>
              <header><div><span>CONFIGURATION</span><h3>配置状态</h3></div><small>{{ activeAgent.prompts.length }} 个 Prompt</small></header>
              <dl class="agent-info-list">
                <div><dt>生产版本</dt><dd><code>{{ activeAgent.publishedVersion }}</code></dd></div>
                <div><dt>配置草稿</dt><dd><code>{{ activeAgent.hasDraft ? activeAgent.draftVersion : "暂无草稿" }}</code></dd></div>
                <div><dt>Prompt 节点</dt><dd>{{ activeAgent.prompts.length }} 个</dd></div>
                <div><dt>回复表达</dt><dd>{{ activeAgent.replyStyle.tone }} · {{ activeAgent.replyStyle.length }}</dd></div>
                <div><dt>最近发布</dt><dd>{{ activeAgent.lastPublishedAt }}</dd></div>
              </dl>
            </section>

            <section class="agent-info-card agent-info-card--governance" data-fixed-card>
              <header>
                <div><span>GOVERNANCE</span><h3>治理状态</h3></div>
                <button type="button" class="text-button" @click="openDetailDialog('profile')">完整权限</button>
              </header>
              <dl class="agent-info-list">
                <div><dt>安全规则</dt><dd><strong>4/4 生效</strong></dd></div>
                <div><dt>Profile 状态</dt><dd>{{ activeAgent.allowedStates.length }} 个可运行状态</dd></div>
                <div><dt>授权能力</dt><dd>{{ activeAgent.skills.length }} Skill · {{ activeAgent.tools.length }} Tool</dd></div>
                <div><dt>禁止动作</dt><dd>{{ activeAgent.forbiddenActions.length }} 项明确禁止</dd></div>
                <div><dt>人工终审</dt><dd>强制保留</dd></div>
              </dl>
            </section>
          </div>
        </section>

        <section
          v-else-if="activeTab === 'overview'"
          id="agent-panel-overview"
          class="agent-tab-panel agent-tab-panel--overview"
          role="tabpanel"
          aria-labelledby="agent-tab-overview"
          data-overview-panel
        >
          <div class="panel-toolbar">
            <div>
              <h3>运行质量概览</h3>
              <p>运行完成只表示数字人在当前阶段产出通过校验，不代表案件闭环或平台最终裁决。</p>
            </div>
            <div class="range-control" aria-label="指标时间范围">
              <button
                v-for="option in rangeOptions"
                :key="option.id"
                type="button"
                :class="{ 'range-control__item--active': activeRange === option.id }"
                :aria-pressed="activeRange === option.id"
                @click="activeRange = option.id"
              >
                {{ option.label }}
              </button>
            </div>
          </div>

          <dl class="metric-strip" data-fixed-card>
            <div v-for="metric in activeMetrics" :key="metric.key" class="metric-cell">
              <dt>{{ metric.label }}</dt>
              <dd><strong>{{ metric.value }}</strong><span>{{ metric.unit }}</span></dd>
              <small :class="`metric-cell__delta--${metric.sentiment}`">环比 {{ metric.delta }}</small>
            </div>
          </dl>

          <div class="overview-content">
            <section class="overview-section overview-section--trend" aria-labelledby="trend-title" data-fixed-card>
              <header class="overview-section__header">
                <div>
                  <h3 id="trend-title">近 7 日任务结果</h3>
                  <p>按任务结束状态统计</p>
                </div>
                <div class="chart-legend" aria-label="图例">
                  <span><i class="legend-dot legend-dot--resolved"></i>完成</span>
                  <span><i class="legend-dot legend-dot--handoff"></i>转人工</span>
                  <span><i class="legend-dot legend-dot--failed"></i>失败</span>
                </div>
              </header>
              <div class="trend-chart" role="img" :aria-label="`${activeAgent.name}近七日完成、转人工和失败趋势`">
                <div v-for="point in activeAgent.trend" :key="point.label" class="trend-chart__column">
                  <div class="trend-chart__bars">
                    <span class="trend-bar trend-bar--resolved" :style="{ height: `${point.resolved}%` }"></span>
                    <span class="trend-bar trend-bar--handoff" :style="{ height: `${point.handoff}%` }"></span>
                    <span class="trend-bar trend-bar--failed" :style="{ height: `${Math.max(point.failed, 4)}%` }"></span>
                  </div>
                  <small>{{ point.label }}</small>
                </div>
              </div>
            </section>

            <section class="overview-section overview-section--quality" aria-labelledby="quality-title" data-fixed-card>
              <header class="overview-section__header">
                <div>
                  <h3 id="quality-title">关键质量指标</h3>
                  <p>来自输出校验与运行审计</p>
                </div>
              </header>
              <div class="quality-list">
                <div v-for="quality in activeAgent.quality" :key="quality.label" class="quality-item">
                  <div><span>{{ quality.label }}</span><strong>{{ quality.value }}%</strong></div>
                  <span
                    class="quality-track"
                    role="progressbar"
                    :aria-label="quality.label"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    :aria-valuenow="quality.value"
                  >
                    <i :style="{ width: `${quality.value}%` }"></i>
                  </span>
                </div>
              </div>
              <div class="quality-summary">
                <strong>{{ activeAgent.budgetLabel }}</strong>
                <span>当前单次运行预算</span>
              </div>
            </section>
            <section class="overview-section overview-section--runs" aria-labelledby="recent-run-title" data-fixed-card>
              <header class="overview-section__header">
                <div>
                  <h3 id="recent-run-title">最近异常与转人工</h3>
                  <p>卡片内滚动浏览，完整记录可展开查看</p>
                </div>
                <button type="button" class="text-button" data-open-run-details @click="openDetailDialog('runs')">查看全部</button>
              </header>
              <div class="run-list" role="table" aria-label="最近异常运行">
                <div class="run-row run-row--header" role="row">
                  <span role="columnheader">运行 ID</span>
                  <span role="columnheader">发生时间</span>
                  <span role="columnheader">问题摘要</span>
                  <span role="columnheader">处理状态</span>
                </div>
                <div v-for="run in activeAgent.recentRuns" :key="run.id" class="run-row" role="row">
                  <strong role="cell">{{ run.id }}</strong>
                  <time role="cell">{{ run.time }}</time>
                  <span role="cell">{{ run.issue }}</span>
                  <em role="cell">{{ run.status }}</em>
                </div>
              </div>
            </section>
          </div>
        </section>

        <section
          v-else-if="activeTab === 'prompt'"
          id="agent-panel-prompt"
          class="agent-tab-panel agent-tab-panel--prompt"
          role="tabpanel"
          aria-labelledby="agent-tab-prompt"
          data-prompt-panel
        >
          <div class="panel-toolbar panel-toolbar--prompt">
            <div>
              <h3>Prompt 配置</h3>
              <p>角色 Prompt 可编辑，通用安全规则和 Profile 权限保持只读。</p>
            </div>
            <span class="editing-state" :class="{ 'editing-state--dirty': activeAgentDirty }">
              {{ activeAgentDirty ? "有未保存修改" : activeAgent.hasDraft ? `${activeAgent.draftVersion} 草稿` : "与生产版本一致" }}
            </span>
          </div>

          <div class="prompt-selector" data-fixed-card>
            <label>
              <span>Prompt 场景</span>
              <select :value="activePrompt.id" @change="selectPrompt($event.target.value)">
                <option v-for="prompt in activeAgent.prompts" :key="prompt.id" :value="prompt.id">
                  {{ prompt.name }} · {{ prompt.type }}
                </option>
              </select>
            </label>
            <dl>
              <div><dt>源文件</dt><dd>{{ activeAgent.promptFile }}</dd></div>
              <div><dt>当前版本</dt><dd>{{ activePrompt.version }}</dd></div>
              <div><dt>字符数</dt><dd>{{ promptCharacterCount.toLocaleString() }}</dd></div>
            </dl>
          </div>

          <div class="prompt-workspace">
            <section class="prompt-editor" aria-labelledby="prompt-editor-title" data-fixed-card>
              <header>
                <div>
                  <span>{{ activePrompt.type }}</span>
                  <h3 id="prompt-editor-title">{{ activePrompt.name }}</h3>
                </div>
                <div class="card-header-actions">
                  <button type="button" class="text-button" data-expand-prompt @click="openDetailDialog('prompt')">展开编辑</button>
                  <button type="button" class="text-button" @click="restorePrompt">重置本次修改</button>
                </div>
              </header>
              <textarea
                v-model="activePrompt.content"
                data-prompt-editor
                :aria-label="`${activeAgent.name}${activePrompt.name}编辑器`"
                spellcheck="false"
                @input="markDirty"
              ></textarea>
              <footer>
                <span>Markdown · UTF-8</span>
                <span>{{ promptCharacterCount.toLocaleString() }} 字符</span>
              </footer>
            </section>

            <aside class="prompt-settings" aria-label="Prompt 表达与变量配置" data-fixed-card>
              <section>
                <header>
                  <div><span>RESPONSE STYLE</span><h3>回复表达</h3></div>
                </header>
                <div class="form-grid">
                  <label>
                    <span>主语气</span>
                    <select v-model="activeAgent.replyStyle.tone" @change="markDirty">
                      <option>专业温和</option>
                      <option>清晰克制</option>
                      <option>审慎直接</option>
                      <option>专业简明</option>
                      <option>耐心共情</option>
                    </select>
                  </label>
                  <label>
                    <span>回复长度</span>
                    <select v-model="activeAgent.replyStyle.length" @change="markDirty">
                      <option>精简</option>
                      <option>标准</option>
                      <option>详细</option>
                    </select>
                  </label>
                  <label>
                    <span>用户称谓</span>
                    <select v-model="activeAgent.replyStyle.address" @change="markDirty">
                      <option>您</option>
                      <option>当前参与方</option>
                      <option>审核员</option>
                    </select>
                  </label>
                </div>
                <div class="slider-list">
                  <label>
                    <span>共情程度 <b>{{ activeAgent.replyStyle.empathy }}</b></span>
                    <input v-model.number="activeAgent.replyStyle.empathy" type="range" min="0" max="100" @input="markDirty" />
                  </label>
                  <label>
                    <span>简洁程度 <b>{{ activeAgent.replyStyle.concision }}</b></span>
                    <input v-model.number="activeAgent.replyStyle.concision" type="range" min="0" max="100" @input="markDirty" />
                  </label>
                  <label>
                    <span>正式程度 <b>{{ activeAgent.replyStyle.formality }}</b></span>
                    <input v-model.number="activeAgent.replyStyle.formality" type="range" min="0" max="100" @input="markDirty" />
                  </label>
                </div>
              </section>

              <section>
                <header>
                  <div><span>VARIABLES</span><h3>可用上下文变量</h3></div>
                  <div class="card-header-actions">
                    <small>{{ activeAgent.variables.length }} 个</small>
                    <button type="button" class="text-button" @click="openDetailDialog('variables')">查看全部</button>
                  </div>
                </header>
                <div class="variable-list">
                  <code v-for="variable in activeAgent.variables" :key="variable">{{ variable }}</code>
                </div>
              </section>

              <section class="safety-panel">
                <header>
                  <div><span>LOCKED</span><h3>不可覆盖的安全边界</h3></div>
                  <div class="card-header-actions">
                    <strong>4/4 生效</strong>
                    <button type="button" class="text-button" @click="openDetailDialog('safety')">查看全部</button>
                  </div>
                </header>
                <ul>
                  <li v-for="rule in promptSafetyRules" :key="rule">{{ rule }}</li>
                </ul>
              </section>
            </aside>
          </div>

          <div class="sticky-action-bar" data-fixed-card>
            <div>
              <strong>{{ activeAgentDirty ? "修改尚未保存" : activeAgent.hasDraft ? "草稿已保存，可发布" : "生产版本已是最新" }}</strong>
              <span>发布后仅影响新启动的数字人任务，进行中的任务继续使用原版本。</span>
            </div>
            <button type="button" class="action-button action-button--secondary" @click="saveDraft">保存草稿</button>
            <button
              type="button"
              class="action-button action-button--primary"
              :disabled="activeAgentDirty || !activeAgent.hasDraft"
              @click="openPublishDialog"
            >
              发布配置
            </button>
          </div>
        </section>

        <section
          v-else-if="activeTab === 'strategy'"
          id="agent-panel-strategy"
          class="agent-tab-panel agent-tab-panel--strategy"
          role="tabpanel"
          aria-labelledby="agent-tab-strategy"
          data-strategy-panel
        >
          <div class="panel-toolbar">
            <div>
              <h3>功能开关与运行策略</h3>
              <p>可变策略进入配置草稿；权限白名单与禁止动作仍由 Agent Profile 锁定。</p>
            </div>
            <span class="editing-state" :class="{ 'editing-state--dirty': activeAgentDirty }">
              {{ activeAgentDirty ? "有未保存修改" : "配置已同步" }}
            </span>
          </div>

          <div class="strategy-layout">
            <div class="capability-groups">
              <section v-for="[group, items] in capabilityGroups" :key="group" class="strategy-section" data-fixed-card>
                <header><h3>{{ group }}</h3><span>{{ items.filter((item) => item.enabled).length }}/{{ items.length }} 开启</span></header>
                <div class="switch-list">
                  <div
                    v-for="capability in items"
                    :key="capability.id"
                    class="switch-row"
                    :class="{ 'switch-row--mode': capability.modeOptions }"
                  >
                    <div>
                      <strong>{{ capability.label }}</strong>
                      <p>{{ capability.description }}</p>
                      <small v-if="capability.locked" class="locked-policy">安全策略锁定</small>
                    </div>
                    <div
                      v-if="capability.modeOptions"
                      class="thinking-mode-control"
                      role="radiogroup"
                      :aria-label="capability.label"
                      :data-capability-id="capability.id"
                    >
                      <button
                        v-for="mode in capability.modeOptions"
                        :key="mode.id"
                        type="button"
                        role="radio"
                        :aria-checked="capability.mode === mode.id"
                        :class="{ 'thinking-mode-control__item--active': capability.mode === mode.id }"
                        :title="mode.description"
                        :data-thinking-mode="mode.id"
                        @click="setCapabilityMode(capability, mode.id)"
                      >
                        {{ mode.label }}
                      </button>
                    </div>
                    <button
                      v-else
                      type="button"
                      class="switch-control switch-control--compact"
                      role="switch"
                      :aria-checked="capability.enabled"
                      :aria-label="`${capability.enabled ? '关闭' : '开启'}${capability.label}`"
                      :disabled="capability.locked"
                      :title="capability.locked ? '安全策略锁定' : undefined"
                      :data-capability-id="capability.id"
                      @click="toggleCapability(capability)"
                    >
                      <span aria-hidden="true"></span>
                    </button>
                  </div>
                </div>
              </section>
            </div>

            <aside class="strategy-aside">
              <section class="strategy-section" data-fixed-card>
                <header><h3>运行阈值</h3><span>草稿可调</span></header>
                <div class="threshold-list">
                  <label>
                    <span>转人工置信度阈值</span>
                    <div><input v-model.number="activeAgent.thresholds.handoffConfidence" type="number" min="0" max="1" step="0.01" @input="markDirty" /><em>0 - 1</em></div>
                  </label>
                  <label>
                    <span>最大重试次数</span>
                    <div><input v-model.number="activeAgent.thresholds.maxRetries" type="number" min="0" max="4" @input="markDirty" /><em>次</em></div>
                  </label>
                  <label>
                    <span>单次运行时限</span>
                    <div><input v-model.number="activeAgent.thresholds.deadlineSeconds" type="number" min="5" max="300" step="5" @input="markDirty" /><em>秒</em></div>
                  </label>
                  <label>
                    <span>最大输出 Token</span>
                    <div><input v-model.number="activeAgent.thresholds.maxOutputTokens" type="number" min="256" max="16000" step="256" @input="markDirty" /><em>Token</em></div>
                  </label>
                </div>
              </section>

              <section class="strategy-section authority-section" data-fixed-card>
                <header>
                  <h3>Profile 权限包络</h3>
                  <div class="card-header-actions">
                    <strong>只读</strong>
                    <button type="button" class="text-button" data-open-profile-details @click="openDetailDialog('profile')">查看全部</button>
                  </div>
                </header>
                <div class="authority-scroll">
                  <div class="authority-block">
                    <span>可运行阶段</span>
                    <div><code v-for="state in activeAgent.allowedStates" :key="state">{{ state }}</code></div>
                  </div>
                  <div class="authority-block">
                    <span>可见上下文域</span>
                    <div><code v-for="scope in activeAgent.contextScopes" :key="scope">{{ scope }}</code></div>
                  </div>
                  <div class="authority-block">
                    <span>已授权 Skill</span>
                    <div><code v-for="skill in activeAgent.skills" :key="skill">{{ skill }}</code></div>
                  </div>
                  <div class="authority-block">
                    <span>已授权 Tool</span>
                    <div><code v-for="tool in activeAgent.tools" :key="tool">{{ tool }}</code></div>
                  </div>
                  <div class="authority-block authority-block--forbidden">
                    <span>明确禁止</span>
                    <ul><li v-for="action in activeAgent.forbiddenActions" :key="action">{{ action }}</li></ul>
                  </div>
                  <div class="authority-block">
                    <span>严格输出 Schema</span>
                    <div><code>{{ activeAgent.outputSchema }}</code></div>
                  </div>
                </div>
              </section>
            </aside>
          </div>
        </section>

        <section
          v-else-if="activeTab === 'debug'"
          id="agent-panel-debug"
          class="agent-tab-panel agent-tab-panel--debug"
          role="tabpanel"
          aria-labelledby="agent-tab-debug"
          data-debug-panel
        >
          <div class="panel-toolbar">
            <div>
              <h3>配置调试台</h3>
              <p>使用当前草稿和脱敏模拟上下文预览回复，不写入案件、不触发业务动作。</p>
            </div>
            <span class="sandbox-label">SANDBOX</span>
          </div>

          <div class="debug-layout">
            <section class="debug-input-panel" data-fixed-card>
              <header><h3>模拟输入</h3><span>{{ activeAgent.draftVersion }}</span></header>
              <label class="debug-message-field">
                <span>参与方身份</span>
                <select v-model="debugActorRole">
                  <option>用户</option>
                  <option>商家</option>
                  <option>平台审核员</option>
                </select>
              </label>
              <label>
                <span>当前消息</span>
                <textarea v-model="debugInput" data-debug-input></textarea>
              </label>
              <div class="debug-context">
                <span>模拟上下文</span>
                <label><input type="checkbox" checked /> 订单引用</label>
                <label><input type="checkbox" checked /> 最近 3 轮对话</label>
                <label><input type="checkbox" /> 附件元数据</label>
              </div>
              <button type="button" class="action-button action-button--primary" data-run-debug @click="runDebug">
                运行测试
              </button>
            </section>

            <section class="debug-output-panel" aria-live="polite" data-fixed-card>
              <header>
                <h3>数字人输出</h3>
                <div class="card-header-actions">
                  <span>{{ debugResult ? "测试完成" : "等待运行" }}</span>
                  <button v-if="debugResult" type="button" class="text-button" data-open-debug-details @click="openDetailDialog('debug')">完整结果</button>
                </div>
              </header>
              <div v-if="!debugResult" class="debug-empty">
                <strong>尚未生成测试结果</strong>
                <span>运行后可查看回复、耗时、Token 和护栏命中情况。</span>
              </div>
              <div v-else class="debug-result-scroll">
                <div class="debug-response" data-debug-response>
                  <img :src="activeAgent.avatar" :alt="activeAgent.name" />
                  <div><strong>{{ activeAgent.displayName }}</strong><p>{{ debugResult.response }}</p></div>
                </div>
                <dl class="debug-metrics">
                  <div><dt>响应耗时</dt><dd>{{ debugResult.latency }}</dd></div>
                  <div><dt>Token 用量</dt><dd>{{ debugResult.tokens }}</dd></div>
                  <div><dt>结构校验</dt><dd>{{ debugResult.schema }}</dd></div>
                  <div><dt>转人工判断</dt><dd>{{ debugResult.handoff }}</dd></div>
                </dl>
                <div class="guardrail-result">
                  <strong>护栏检查</strong>
                  <ul><li v-for="guardrail in debugResult.guardrails" :key="guardrail">{{ guardrail }}</li></ul>
                </div>
              </div>
            </section>
          </div>
        </section>

        <section
          v-else
          id="agent-panel-versions"
          class="agent-tab-panel agent-tab-panel--versions"
          role="tabpanel"
          aria-labelledby="agent-tab-versions"
          data-versions-panel
        >
          <div class="panel-toolbar">
            <div>
              <h3>版本发布与审计</h3>
              <p>生产版本不可直接覆盖；历史版本只能先创建草稿，再经过发布确认。</p>
            </div>
            <button type="button" class="action-button action-button--primary" :disabled="activeAgentDirty || !activeAgent.hasDraft" @click="openPublishDialog">
              发布当前草稿
            </button>
          </div>

          <div class="release-summary" data-fixed-card>
            <div>
              <span>生产环境</span>
              <strong>{{ activeAgent.publishedVersion }}</strong>
              <small>{{ activeAgent.lastPublishedAt }} · 全量流量</small>
            </div>
            <i aria-hidden="true"></i>
            <div>
              <span>配置草稿</span>
              <strong>{{ activeAgent.hasDraft ? activeAgent.draftVersion : "暂无草稿" }}</strong>
              <small>{{ activeAgent.hasDraft ? "待保存并发布" : "与生产版本一致" }}</small>
            </div>
          </div>

          <div class="version-list" aria-label="配置版本记录" data-fixed-card>
            <article v-for="version in activeAgent.versions" :key="`${version.version}:${version.status}`" class="version-row">
              <div class="version-row__marker" :class="`version-row__marker--${version.status}`" aria-hidden="true"></div>
              <div class="version-row__main">
                <div>
                  <strong>{{ version.version }}</strong>
                  <span :class="`version-status version-status--${version.status}`">{{ version.statusLabel }}</span>
                </div>
                <p>{{ version.summary }}</p>
                <small>{{ version.operator }} · {{ version.time }}</small>
              </div>
              <div class="version-row__actions">
                <button type="button" class="text-button" data-open-version-details @click="openDetailDialog('version', version)">查看差异</button>
                <button
                  v-if="version.status === 'archived'"
                  type="button"
                  class="text-button"
                  @click="createRollbackDraft(version)"
                >
                  基于此版本创建草稿
                </button>
              </div>
            </article>
          </div>
        </section>
      </main>
    </div>

    <Teleport to="body">
      <div
        v-if="avatarDialogOpen"
        class="avatar-dialog-backdrop"
        role="presentation"
        @click.self="closeAvatarDialog"
      >
        <section
          class="avatar-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="avatar-dialog-title"
          @keydown.esc="closeAvatarDialog"
        >
          <header>
            <div>
              <span>DIGITAL HUMAN APPEARANCE</span>
              <h2 id="avatar-dialog-title">更换 {{ activeAgent.name }} 形象</h2>
              <p>选择预设形象或上传本地图片，确认后进入当前配置草稿。</p>
            </div>
            <button type="button" class="dialog-close" aria-label="关闭形象选择" title="关闭" @click="closeAvatarDialog">×</button>
          </header>

          <div class="avatar-dialog__content">
            <div class="avatar-dialog__preview">
              <span>当前预览</span>
              <div><img :src="pendingAvatar" :alt="`${activeAgent.name}待选形象`" /></div>
              <strong>{{ uploadedAvatarName || activeAgent.displayName }}</strong>
              <small>建议使用透明背景、正方形构图</small>
            </div>

            <div class="avatar-dialog__picker">
              <strong>预设形象</strong>
              <div class="avatar-preset-grid">
                <button
                  v-for="avatar in digitalHumanAvatarOptions"
                  :key="avatar.id"
                  type="button"
                  :class="{ 'avatar-preset--active': pendingAvatar === avatar.src }"
                  :aria-pressed="pendingAvatar === avatar.src"
                  :data-avatar-option="avatar.id"
                  @click="selectAvatar(avatar)"
                >
                  <img :src="avatar.src" alt="" />
                  <span>{{ avatar.label }}</span>
                </button>
              </div>

              <label class="avatar-upload-button">
                <input type="file" accept="image/png,image/jpeg,image/webp" data-avatar-upload @change="uploadAvatar" />
                <span>上传本地形象</span>
                <small>PNG、JPG 或 WebP，最大 2MB</small>
              </label>
            </div>
          </div>

          <footer>
            <button type="button" class="action-button action-button--secondary" @click="closeAvatarDialog">取消</button>
            <button
              type="button"
              class="action-button action-button--primary"
              :disabled="!avatarSelectionChanged"
              data-confirm-avatar
              @click="confirmAvatarChange"
            >
              使用这个形象
            </button>
          </footer>
        </section>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="detailDialogKind"
        class="detail-dialog-backdrop"
        role="presentation"
        @click.self="closeDetailDialog"
      >
        <section
          class="detail-dialog"
          :class="`detail-dialog--${detailDialogKind}`"
          role="dialog"
          aria-modal="true"
          aria-labelledby="detail-dialog-title"
          data-detail-dialog
          @keydown.esc="closeDetailDialog"
        >
          <header>
            <div>
              <span>{{ detailDialogMeta.eyebrow }}</span>
              <h2 id="detail-dialog-title">{{ detailDialogMeta.title }}</h2>
              <p>{{ activeAgent.name }} · {{ activeAgent.publishedVersion }}</p>
            </div>
            <button type="button" class="dialog-close" aria-label="关闭详情" title="关闭" @click="closeDetailDialog">×</button>
          </header>

          <div class="detail-dialog__body">
            <div v-if="detailDialogKind === 'runs'" class="detail-run-table" role="table" aria-label="全部异常与转人工记录">
              <div class="detail-run-row detail-run-row--header" role="row">
                <span role="columnheader">运行 ID</span>
                <span role="columnheader">发生时间</span>
                <span role="columnheader">问题摘要</span>
                <span role="columnheader">处理状态</span>
              </div>
              <div v-for="run in activeAgent.recentRuns" :key="run.id" class="detail-run-row" role="row">
                <strong role="cell">{{ run.id }}</strong>
                <time role="cell">{{ run.time }}</time>
                <span role="cell">{{ run.issue }}</span>
                <em role="cell">{{ run.status }}</em>
              </div>
            </div>

            <div v-else-if="detailDialogKind === 'prompt'" class="detail-prompt-editor">
              <div>
                <span>{{ activePrompt.type }} · Markdown · UTF-8</span>
                <strong>{{ promptCharacterCount.toLocaleString() }} 字符</strong>
              </div>
              <textarea
                v-model="activePrompt.content"
                data-expanded-prompt-editor
                :aria-label="`${activeAgent.name}${activePrompt.name}展开编辑器`"
                spellcheck="false"
                @input="markDirty"
              ></textarea>
            </div>

            <div v-else-if="detailDialogKind === 'variables'" class="detail-token-grid">
              <code v-for="variable in activeAgent.variables" :key="variable">{{ variable }}</code>
            </div>

            <ul v-else-if="detailDialogKind === 'safety'" class="detail-rule-list">
              <li v-for="rule in promptSafetyRules" :key="rule"><strong>强制生效</strong><span>{{ rule }}</span></li>
            </ul>

            <div v-else-if="detailDialogKind === 'profile'" class="detail-profile-grid">
              <section>
                <span>可运行阶段</span>
                <div><code v-for="state in activeAgent.allowedStates" :key="state">{{ state }}</code></div>
              </section>
              <section>
                <span>可见上下文域</span>
                <div><code v-for="scope in activeAgent.contextScopes" :key="scope">{{ scope }}</code></div>
              </section>
              <section>
                <span>已授权 Skill</span>
                <div><code v-for="skill in activeAgent.skills" :key="skill">{{ skill }}</code></div>
              </section>
              <section>
                <span>已授权 Tool</span>
                <div><code v-for="tool in activeAgent.tools" :key="tool">{{ tool }}</code></div>
              </section>
              <section class="detail-profile-grid__wide">
                <span>明确禁止</span>
                <ul><li v-for="action in activeAgent.forbiddenActions" :key="action">{{ action }}</li></ul>
              </section>
              <section class="detail-profile-grid__wide">
                <span>严格输出 Schema</span>
                <div><code>{{ activeAgent.outputSchema }}</code></div>
              </section>
            </div>

            <div v-else-if="detailDialogKind === 'debug' && debugResult" class="detail-debug-result">
              <div class="debug-response">
                <img :src="activeAgent.avatar" :alt="activeAgent.name" />
                <div><strong>{{ activeAgent.displayName }}</strong><p>{{ debugResult.response }}</p></div>
              </div>
              <dl class="debug-metrics">
                <div><dt>响应耗时</dt><dd>{{ debugResult.latency }}</dd></div>
                <div><dt>Token 用量</dt><dd>{{ debugResult.tokens }}</dd></div>
                <div><dt>结构校验</dt><dd>{{ debugResult.schema }}</dd></div>
                <div><dt>转人工判断</dt><dd>{{ debugResult.handoff }}</dd></div>
              </dl>
              <div class="guardrail-result">
                <strong>护栏检查</strong>
                <ul><li v-for="guardrail in debugResult.guardrails" :key="guardrail">{{ guardrail }}</li></ul>
              </div>
            </div>

            <div v-else-if="detailDialogKind === 'version' && detailVersion" class="detail-version-grid">
              <section><span>查看版本</span><strong>{{ detailVersion.version }}</strong><small>{{ detailVersion.statusLabel }}</small></section>
              <section><span>当前生产</span><strong>{{ activeAgent.publishedVersion }}</strong><small>只比较配置，不直接覆盖</small></section>
              <section class="detail-version-grid__summary">
                <span>变更摘要</span>
                <p>{{ detailVersion.summary }}</p>
              </section>
              <section><span>操作人</span><strong>{{ detailVersion.operator }}</strong><small>{{ detailVersion.time }}</small></section>
              <section><span>影响范围</span><strong>仅新启动任务</strong><small>运行中任务继续使用原版本</small></section>
            </div>
          </div>

          <footer>
            <span>展开内容保持只读边界；Prompt 编辑会继续记录到当前配置草稿。</span>
            <button type="button" class="action-button action-button--primary" @click="closeDetailDialog">完成</button>
          </footer>
        </section>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="publishDialogOpen"
        class="publish-dialog-backdrop"
        role="presentation"
        @click.self="closePublishDialog"
      >
        <section
          class="publish-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="publish-dialog-title"
          @keydown.esc="closePublishDialog"
        >
          <header>
            <div>
              <span>PRODUCTION RELEASE</span>
              <h2 id="publish-dialog-title">发布 {{ activeAgent.name }} 配置</h2>
            </div>
            <button type="button" class="dialog-close" aria-label="关闭发布确认" title="关闭" @click="closePublishDialog">×</button>
          </header>
          <div class="publish-dialog__impact">
            <div><span>当前生产</span><strong>{{ activeAgent.publishedVersion }}</strong></div>
            <i aria-hidden="true">→</i>
            <div><span>待发布草稿</span><strong>{{ activeAgent.draftVersion }}</strong></div>
          </div>
          <ul>
            <li>当前是交互原型，确认操作只更新页面内示例状态，不影响任何运行服务。</li>
            <li>真实系统中，新启动的任务使用新版本，进行中的任务继续使用原版本。</li>
            <li>通用安全规则、Profile 权限和人工终审边界不会随 Prompt 发布而改变。</li>
            <li>发布操作会写入版本记录，可从历史版本创建回滚草稿。</li>
          </ul>
          <label>
            <span>发布说明</span>
            <textarea v-model="publishNote" maxlength="200" placeholder="说明本次调整内容与验证结果"></textarea>
            <small>{{ publishNote.length }}/200</small>
          </label>
          <footer>
            <button type="button" class="action-button action-button--secondary" @click="closePublishDialog">取消</button>
            <button type="button" class="action-button action-button--primary" :disabled="!publishNote.trim()" data-confirm-publish @click="confirmPublish">
              确认发布到生产
            </button>
          </footer>
        </section>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.agent-console {
  --ink-strong: #263754;
  --ink: #34435c;
  --ink-muted: #71809a;
  --line: #dfe8f4;
  --line-strong: #cbd9e9;
  --surface: #ffffffd9;
  --surface-solid: #ffffff;
  --surface-subtle: #f8fbff;
  --surface-muted: #eef3ff;
  --accent: #6279ca;
  --accent-soft: #eef3ff;
  --teal: #52c790;
  --teal-soft: #e7f8ef;
  --amber: #9a6a18;
  --amber-soft: #fff4d7;
  --red: #b24b5d;
  --red-soft: #fff0f3;
  --purple: #6e5799;
  --purple-soft: #f1edff;
  display: grid;
  min-width: 0;
  gap: 20px;
  color: var(--ink);
  letter-spacing: 0;
}

.agent-console,
.agent-console * {
  box-sizing: border-box;
}

.agent-console :where(header, aside, main, section, article, div, nav, button, span, strong, small, em, p, h1, h2, h3, dl, dt, dd, label, input, textarea, select, code, ul, li) {
  min-width: 0;
}

.agent-console :where(span, strong, small, em, p, h1, h2, h3, dt, dd, label, code, li, button) {
  overflow-wrap: anywhere;
  word-break: break-word;
}

.agent-console__header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
  min-height: 78px;
  padding: 2px 2px 0;
}

.agent-console__title > span,
.agent-detail-header__identity > div > div > span,
.prompt-settings header span,
.publish-dialog header span {
  color: var(--ink-muted);
  font-size: 10px;
  font-weight: 800;
  line-height: 1.2;
}

.agent-console__title h1 {
  margin: 4px 0 5px;
  color: var(--ink-strong);
  font-size: 30px;
  line-height: 1.16;
}

.agent-console__description {
  margin: 0;
  color: var(--ink-muted);
  font-size: 13px;
  line-height: 1.55;
}

.agent-console__header-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.fleet-status {
  display: grid;
  gap: 2px;
  padding-right: 12px;
  border-right: 1px solid var(--line);
}

.fleet-status span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--ink-strong);
  font-size: 12px;
  font-weight: 750;
}

.fleet-status span i {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  background: var(--teal);
  border-radius: 50%;
  box-shadow: 0 0 0 3px var(--teal-soft);
}

.fleet-status small {
  color: var(--ink-muted);
  font-size: 10px;
}

.action-button,
.text-button,
.dialog-close {
  border: 0;
  cursor: pointer;
}

.action-button {
  min-height: 38px;
  padding: 8px 14px;
  border-radius: 5px;
  font-size: 12px;
  font-weight: 750;
  line-height: 1.2;
}

.action-button--primary {
  color: #fff;
  background: var(--accent);
  border: 1px solid var(--accent);
}

.action-button--primary:hover:not(:disabled) {
  background: #254b64;
}

.action-button--secondary {
  color: var(--ink);
  background: var(--surface);
  border: 1px solid var(--line-strong);
}

.action-button:disabled {
  color: #98a2ad;
  background: #edf0f3;
  border-color: #e0e4e8;
  cursor: not-allowed;
}

.text-button {
  padding: 4px 0;
  color: var(--accent);
  background: transparent;
  font-size: 11px;
  font-weight: 750;
}

.agent-manager {
  display: grid;
  grid-template-columns: 246px minmax(0, 1fr);
  min-height: 800px;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 8px;
  box-shadow: 0 12px 30px rgba(37, 54, 71, 0.06);
}

.agent-sidebar {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 16px 12px;
  background: var(--surface-subtle);
  border-right: 1px solid var(--line);
}

.agent-sidebar__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 4px 12px;
}

.agent-sidebar__heading > div {
  display: flex;
  align-items: center;
  gap: 7px;
}

.agent-sidebar__heading span {
  color: var(--ink-strong);
  font-size: 13px;
  font-weight: 800;
}

.agent-sidebar__heading strong {
  display: grid;
  min-width: 22px;
  height: 22px;
  padding: 0 5px;
  place-items: center;
  color: var(--accent);
  background: var(--accent-soft);
  border-radius: 4px;
  font-size: 11px;
}

.agent-sidebar__heading small {
  color: var(--teal);
  font-size: 10px;
  font-weight: 750;
}

.mobile-agent-toggle {
  display: none;
}

.agent-picker {
  display: contents;
}

.agent-search {
  display: grid;
  gap: 5px;
  padding: 0 4px 12px;
}

.agent-search > span,
.form-grid label > span,
.debug-input-panel label > span,
.threshold-list label > span,
.publish-dialog > label > span {
  color: var(--ink-muted);
  font-size: 10px;
  font-weight: 750;
}

.agent-search input,
.form-grid select,
.prompt-selector select,
.debug-input-panel select,
.threshold-list input {
  width: 100%;
  min-height: 36px;
  padding: 7px 9px;
  color: var(--ink-strong);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 5px;
  outline: 0;
  font-size: 11px;
}

.agent-search input:focus,
.form-grid select:focus,
.prompt-selector select:focus,
.debug-input-panel select:focus,
.threshold-list input:focus,
.prompt-editor textarea:focus,
.debug-input-panel textarea:focus,
.publish-dialog textarea:focus {
  border-color: #7fa3b9;
  box-shadow: 0 0 0 3px rgba(49, 92, 120, 0.12);
}

.agent-list {
  display: grid;
  gap: 5px;
}

.agent-list-item {
  position: relative;
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  gap: 9px;
  align-items: center;
  width: 100%;
  min-height: 67px;
  padding: 8px;
  color: inherit;
  text-align: left;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
}

.agent-list-item:hover {
  background: #edf1f4;
}

.agent-list-item--active {
  background: var(--surface);
  border-color: var(--line-strong);
  box-shadow: 0 4px 12px rgba(37, 54, 71, 0.06);
}

.agent-list-item__avatar {
  position: relative;
  display: grid;
  width: 44px;
  height: 48px;
  place-items: end center;
  overflow: hidden;
  background: #e9eef2;
  border: 1px solid #d6dde3;
  border-radius: 6px;
}

.agent-list-item[data-agent-id="evidence_clerk"] .agent-list-item__avatar,
.agent-detail-header[data-agent-id="evidence_clerk"] .agent-detail-header__portrait {
  background: linear-gradient(145deg, #eaf4ff, #f3f8ff);
}

.agent-list-item[data-agent-id="presiding_judge"] .agent-list-item__avatar,
.agent-detail-header[data-agent-id="presiding_judge"] .agent-detail-header__portrait {
  background: linear-gradient(145deg, #fff0eb, #fff8e3);
}

.agent-list-item[data-agent-id="jury_reviewer"] .agent-list-item__avatar,
.agent-detail-header[data-agent-id="jury_reviewer"] .agent-detail-header__portrait {
  background: linear-gradient(145deg, #f0edff, #f8f1ff);
}

.agent-list-item[data-agent-id="review_copilot"] .agent-list-item__avatar,
.agent-detail-header[data-agent-id="review_copilot"] .agent-detail-header__portrait {
  background: linear-gradient(145deg, #fff0f7, #f2ebff);
}

.agent-list-item__avatar img {
  width: 44px;
  height: 44px;
  object-fit: contain;
  object-position: center bottom;
}

.status-dot {
  position: absolute;
  right: 3px;
  bottom: 3px;
  width: 8px;
  height: 8px;
  background: var(--teal);
  border: 2px solid #fff;
  border-radius: 50%;
}

.status-dot--attention { background: var(--amber); }
.status-dot--offline { background: #8a949f; }

.agent-list-item__copy {
  display: grid;
  gap: 2px;
}

.agent-list-item__copy strong {
  color: var(--ink-strong);
  font-size: 12px;
}

.agent-list-item__copy small {
  color: var(--ink-muted);
  font-size: 10px;
}

.agent-list-item__copy em {
  color: #87919c;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 9px;
  font-style: normal;
}

.draft-mark {
  align-self: start;
  padding: 2px 4px;
  color: var(--purple);
  background: var(--purple-soft);
  border-radius: 3px;
  font-size: 8px;
  font-weight: 800;
}

.agent-sidebar__empty {
  padding: 24px 6px;
  margin: 0;
  color: var(--ink-muted);
  font-size: 11px;
  text-align: center;
}

.governance-note {
  display: grid;
  gap: 4px;
  padding: 12px;
  margin-top: auto;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 6px;
}

.governance-note strong {
  color: var(--ink-strong);
  font-size: 11px;
}

.governance-note span {
  color: var(--ink-muted);
  font-size: 9px;
  line-height: 1.5;
}

.agent-workbench {
  min-width: 0;
  background: var(--surface);
}

.agent-detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  min-height: 132px;
  padding: 18px 22px;
  border-bottom: 1px solid var(--line);
}

.agent-detail-header__identity {
  display: flex;
  align-items: center;
  gap: 14px;
}

.agent-detail-header__portrait {
  display: grid;
  width: 88px;
  height: 96px;
  flex: 0 0 auto;
  place-items: end center;
  overflow: hidden;
  background: #edf2f3;
  border: 1px solid #d5dfe2;
  border-radius: 8px;
}

.agent-detail-header__portrait img {
  width: 88px;
  height: 90px;
  object-fit: contain;
  object-position: center bottom;
}

.agent-detail-header h2 {
  margin: 4px 0 5px;
  color: var(--ink-strong);
  font-size: 21px;
  line-height: 1.25;
}

.agent-detail-header h2 small {
  color: var(--accent);
  font-size: 12px;
  font-weight: 750;
}

.agent-detail__summary {
  max-width: 650px;
  margin: 0;
  color: var(--ink-muted);
  font-size: 11px;
  line-height: 1.55;
}

.agent-detail-header__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}

.agent-detail-header__tags > span {
  padding: 3px 6px;
  color: #566370;
  background: var(--surface-subtle);
  border: 1px solid var(--line);
  border-radius: 4px;
  font-size: 9px;
  font-weight: 700;
}

.agent-detail-header__tags .health-label--healthy {
  color: var(--teal);
  background: var(--teal-soft);
  border-color: #c4e2dc;
}

.agent-detail-header__tags .health-label--attention {
  color: var(--amber);
  background: var(--amber-soft);
  border-color: #ecd59f;
}

.agent-detail-header__runtime {
  display: flex;
  align-items: center;
  gap: 16px;
}

.agent-detail-header__runtime > div {
  display: grid;
  gap: 2px;
  min-width: 150px;
  padding-right: 16px;
  border-right: 1px solid var(--line);
}

.agent-detail-header__runtime small,
.agent-detail-header__runtime span {
  color: var(--ink-muted);
  font-size: 9px;
}

.agent-detail-header__runtime strong {
  color: var(--ink-strong);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}

.switch-control {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 34px;
  padding: 5px 8px;
  color: var(--teal);
  background: transparent;
  border: 0;
  border-radius: 5px;
  cursor: pointer;
  font-size: 10px;
  font-weight: 750;
}

.switch-control > span {
  position: relative;
  width: 32px;
  height: 18px;
  flex: 0 0 auto;
  background: #aeb7c0;
  border-radius: 9px;
  transition: background-color 0.16s ease;
}

.switch-control > span::after {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 12px;
  height: 12px;
  content: "";
  background: #fff;
  border-radius: 50%;
  box-shadow: 0 1px 3px rgba(24, 40, 54, 0.28);
  transition: transform 0.16s ease;
}

.switch-control[aria-checked="true"] > span { background: var(--teal); }
.switch-control[aria-checked="true"] > span::after { transform: translateX(14px); }
.switch-control[aria-checked="false"] { color: var(--ink-muted); }
.switch-control:disabled { cursor: not-allowed; opacity: 0.68; }

.switch-control--compact {
  min-width: 44px;
  justify-content: flex-end;
  padding: 5px 0;
}

.agent-tabs {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  padding: 0 22px;
  border-bottom: 1px solid var(--line);
}

.agent-tabs button {
  position: relative;
  min-height: 48px;
  padding: 8px 10px;
  color: var(--ink-muted);
  background: transparent;
  border: 0;
  cursor: pointer;
  font-size: 11px;
  font-weight: 750;
}

.agent-tabs button::after {
  position: absolute;
  right: 10px;
  bottom: -1px;
  left: 10px;
  height: 2px;
  content: "";
  background: transparent;
}

.agent-tabs .agent-tabs__item--active {
  color: var(--ink-strong);
}

.agent-tabs .agent-tabs__item--active::after {
  background: var(--accent);
}

.agent-tab-panel {
  display: grid;
  gap: 16px;
  padding: 20px 22px 24px;
}

.panel-toolbar,
.overview-section__header,
.strategy-section > header,
.debug-input-panel > header,
.debug-output-panel > header,
.prompt-editor > header,
.prompt-settings section > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.panel-toolbar h3,
.overview-section__header h3,
.strategy-section > header h3,
.debug-input-panel > header h3,
.debug-output-panel > header h3,
.prompt-editor > header h3,
.prompt-settings section > header h3 {
  margin: 0;
  color: var(--ink-strong);
  font-size: 14px;
  line-height: 1.35;
}

.panel-toolbar p,
.overview-section__header p {
  margin: 4px 0 0;
  color: var(--ink-muted);
  font-size: 10px;
  line-height: 1.45;
}

.range-control {
  display: inline-grid;
  grid-template-columns: repeat(3, 1fr);
  padding: 2px;
  background: var(--surface-muted);
  border-radius: 6px;
}

.range-control button {
  min-height: 30px;
  padding: 5px 10px;
  color: var(--ink-muted);
  background: transparent;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  font-size: 10px;
  font-weight: 700;
}

.range-control .range-control__item--active {
  color: var(--ink-strong);
  background: var(--surface);
  box-shadow: 0 1px 3px rgba(34, 50, 65, 0.12);
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 7px;
}

.metric-cell {
  display: grid;
  gap: 5px;
  min-height: 104px;
  padding: 15px;
  background: var(--surface);
  border-right: 1px solid var(--line);
}

.metric-cell:last-child { border-right: 0; }
.metric-cell dt { color: var(--ink-muted); font-size: 10px; font-weight: 700; }
.metric-cell dd { display: flex; align-items: baseline; gap: 4px; margin: 0; }
.metric-cell dd strong { color: var(--ink-strong); font-size: 23px; line-height: 1; }
.metric-cell dd span { color: var(--ink-muted); font-size: 9px; }
.metric-cell small { font-size: 9px; }
.metric-cell__delta--positive { color: var(--teal); }
.metric-cell__delta--negative { color: var(--red); }
.metric-cell__delta--neutral { color: var(--amber); }

.overview-content {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(280px, 0.9fr);
  gap: 14px;
}

.overview-section,
.prompt-editor,
.prompt-settings,
.strategy-section,
.debug-input-panel,
.debug-output-panel {
  min-width: 0;
  padding: 16px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 7px;
}

.overview-section__header {
  align-items: start;
  margin-bottom: 14px;
}

.chart-legend {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 9px;
}

.chart-legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-muted);
  font-size: 9px;
}

.legend-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  border-radius: 2px;
}

.legend-dot--resolved,
.trend-bar--resolved { background: var(--teal); }
.legend-dot--handoff,
.trend-bar--handoff { background: var(--amber); }
.legend-dot--failed,
.trend-bar--failed { background: var(--red); }

.trend-chart {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 10px;
  height: 210px;
  padding: 8px 8px 0;
  border-bottom: 1px solid var(--line);
  background-image: linear-gradient(to bottom, transparent 24%, #edf0f2 25%, transparent 26%, transparent 49%, #edf0f2 50%, transparent 51%, transparent 74%, #edf0f2 75%, transparent 76%);
}

.trend-chart__column {
  display: grid;
  grid-template-rows: minmax(0, 1fr) 22px;
  gap: 5px;
  align-items: end;
}

.trend-chart__bars {
  display: flex;
  align-items: end;
  justify-content: center;
  gap: 3px;
  height: 100%;
}

.trend-bar {
  display: block;
  width: min(12px, 30%);
  min-height: 3px;
  border-radius: 2px 2px 0 0;
}

.trend-chart__column small {
  color: var(--ink-muted);
  font-size: 9px;
  text-align: center;
}

.quality-list {
  display: grid;
  gap: 18px;
  padding: 8px 0 16px;
}

.quality-item {
  display: grid;
  gap: 7px;
}

.quality-item > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.quality-item span { color: var(--ink); font-size: 10px; }
.quality-item strong { color: var(--ink-strong); font-size: 11px; }

.quality-track {
  display: block;
  width: 100%;
  height: 6px;
  overflow: hidden;
  background: var(--surface-muted);
  border-radius: 3px;
}

.quality-track i {
  display: block;
  height: 100%;
  background: var(--teal);
  border-radius: inherit;
}

.quality-summary {
  display: grid;
  gap: 3px;
  padding: 12px;
  background: var(--accent-soft);
  border-radius: 6px;
}

.quality-summary strong { color: var(--accent); font-size: 12px; }
.quality-summary span { color: #607482; font-size: 9px; }

.run-list {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 6px;
}

.run-row {
  display: grid;
  grid-template-columns: 100px 90px minmax(0, 1fr) 88px;
  gap: 12px;
  align-items: center;
  min-height: 42px;
  padding: 8px 11px;
  border-bottom: 1px solid var(--line);
  font-size: 10px;
}

.run-row:last-child { border-bottom: 0; }
.run-row--header { min-height: 34px; color: var(--ink-muted); background: var(--surface-subtle); font-weight: 750; }
.run-row strong { color: var(--accent); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 9px; }
.run-row time { color: var(--ink-muted); }
.run-row em { color: var(--amber); font-style: normal; font-weight: 750; }

.editing-state,
.sandbox-label {
  flex: 0 0 auto;
  padding: 5px 8px;
  color: var(--purple);
  background: var(--purple-soft);
  border: 1px solid #d8d0ec;
  border-radius: 4px;
  font-size: 9px;
  font-weight: 800;
}

.editing-state--dirty {
  color: var(--amber);
  background: var(--amber-soft);
  border-color: #ead49c;
}

.prompt-selector {
  display: grid;
  grid-template-columns: minmax(210px, 0.6fr) minmax(0, 1.4fr);
  gap: 14px;
  align-items: end;
  padding: 12px 14px;
  background: var(--surface-subtle);
  border: 1px solid var(--line);
  border-radius: 7px;
}

.prompt-selector > label {
  display: grid;
  gap: 5px;
}

.prompt-selector > label > span {
  color: var(--ink-muted);
  font-size: 9px;
  font-weight: 750;
}

.prompt-selector dl {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px 80px;
  gap: 12px;
  margin: 0;
}

.prompt-selector dl div {
  display: grid;
  gap: 3px;
}

.prompt-selector dt { color: var(--ink-muted); font-size: 9px; }
.prompt-selector dd { margin: 0; color: var(--ink-strong); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 9px; }

.prompt-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 310px;
  gap: 14px;
  align-items: start;
}

.prompt-editor {
  display: grid;
  grid-template-rows: auto minmax(440px, 1fr) auto;
  padding: 0;
  overflow: hidden;
}

.prompt-editor > header {
  min-height: 58px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--line);
}

.prompt-editor > header span { color: var(--ink-muted); font-size: 9px; }
.prompt-editor > header h3 { margin-top: 3px; }

.prompt-editor textarea {
  width: 100%;
  min-height: 440px;
  padding: 16px;
  resize: vertical;
  color: #263746;
  background: #fbfcfd;
  border: 0;
  outline: 0;
  font-family: ui-monospace, SFMono-Regular, Consolas, "Microsoft YaHei", monospace;
  font-size: 12px;
  line-height: 1.75;
  tab-size: 2;
}

.prompt-editor > footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 14px;
  color: var(--ink-muted);
  background: var(--surface-subtle);
  border-top: 1px solid var(--line);
  font-size: 9px;
}

.prompt-settings {
  display: grid;
  gap: 0;
  padding: 0;
  overflow: hidden;
}

.prompt-settings section {
  display: grid;
  gap: 12px;
  padding: 14px;
  border-bottom: 1px solid var(--line);
}

.prompt-settings section:last-child { border-bottom: 0; }
.prompt-settings section > header small { color: var(--ink-muted); font-size: 9px; }
.prompt-settings section > header strong { color: var(--teal); font-size: 9px; }

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
}

.form-grid label {
  display: grid;
  gap: 5px;
}

.form-grid label:first-child { grid-column: 1 / -1; }

.slider-list {
  display: grid;
  gap: 10px;
  padding-top: 3px;
}

.slider-list label {
  display: grid;
  gap: 5px;
}

.slider-list label > span {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  color: var(--ink-muted);
  font-size: 9px;
}

.slider-list b { color: var(--ink-strong); }
.slider-list input { width: 100%; accent-color: var(--teal); }

.variable-list,
.authority-block > div {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.variable-list code,
.authority-block code {
  padding: 4px 5px;
  color: var(--accent);
  background: var(--accent-soft);
  border: 1px solid #cedfe7;
  border-radius: 3px;
  font-size: 8px;
}

.safety-panel ul,
.authority-block ul,
.guardrail-result ul,
.publish-dialog ul {
  display: grid;
  gap: 7px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.safety-panel li,
.authority-block li,
.guardrail-result li,
.publish-dialog li {
  position: relative;
  padding-left: 13px;
  color: var(--ink-muted);
  font-size: 9px;
  line-height: 1.5;
}

.safety-panel li::before,
.authority-block li::before,
.guardrail-result li::before,
.publish-dialog li::before {
  position: absolute;
  top: 0.6em;
  left: 2px;
  width: 4px;
  height: 4px;
  content: "";
  background: var(--teal);
  border-radius: 50%;
}

.sticky-action-bar {
  position: sticky;
  bottom: 8px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
  padding: 11px 12px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--line-strong);
  border-radius: 7px;
  box-shadow: 0 8px 20px rgba(34, 50, 65, 0.12);
  backdrop-filter: blur(10px);
}

.sticky-action-bar > div {
  display: grid;
  gap: 2px;
}

.sticky-action-bar strong { color: var(--ink-strong); font-size: 10px; }
.sticky-action-bar span { color: var(--ink-muted); font-size: 9px; }

.strategy-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(310px, 0.8fr);
  gap: 14px;
  align-items: start;
}

.capability-groups,
.strategy-aside {
  display: grid;
  gap: 14px;
}

.strategy-section > header {
  padding-bottom: 11px;
  border-bottom: 1px solid var(--line);
}

.strategy-section > header span,
.strategy-section > header strong,
.debug-input-panel > header span,
.debug-output-panel > header span {
  color: var(--ink-muted);
  font-size: 9px;
}

.authority-section > header strong { color: var(--purple); }

.switch-list {
  display: grid;
}

.switch-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
  min-height: 70px;
  padding: 11px 0;
  border-bottom: 1px solid var(--line);
}

.switch-row:last-child { padding-bottom: 0; border-bottom: 0; }
.switch-row strong { color: var(--ink-strong); font-size: 11px; }
.switch-row p { margin: 4px 0 0; color: var(--ink-muted); font-size: 9px; line-height: 1.5; }

.locked-policy {
  display: inline-block;
  margin-top: 5px;
  color: var(--purple);
  font-size: 8px;
  font-weight: 750;
}

.threshold-list {
  display: grid;
  gap: 13px;
  padding-top: 13px;
}

.threshold-list label {
  display: grid;
  gap: 6px;
}

.threshold-list label > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 66px;
  align-items: center;
  border: 1px solid var(--line);
  border-radius: 5px;
}

.threshold-list input {
  min-height: 34px;
  border: 0;
}

.threshold-list em {
  padding: 0 8px;
  color: var(--ink-muted);
  border-left: 1px solid var(--line);
  font-size: 8px;
  font-style: normal;
  text-align: center;
}

.authority-block {
  display: grid;
  gap: 7px;
  padding-top: 13px;
}

.authority-block > span {
  color: var(--ink-muted);
  font-size: 9px;
  font-weight: 750;
}

.authority-block--forbidden li::before { background: var(--red); }

.debug-layout {
  display: grid;
  grid-template-columns: minmax(300px, 0.8fr) minmax(0, 1.2fr);
  gap: 14px;
  align-items: stretch;
}

.debug-input-panel,
.debug-output-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 530px;
}

.debug-input-panel > header,
.debug-output-panel > header {
  padding-bottom: 11px;
  border-bottom: 1px solid var(--line);
}

.debug-input-panel > label {
  display: grid;
  gap: 6px;
}

.debug-input-panel textarea,
.publish-dialog textarea {
  width: 100%;
  min-height: 180px;
  padding: 10px;
  resize: vertical;
  color: var(--ink-strong);
  background: var(--surface-subtle);
  border: 1px solid var(--line);
  border-radius: 5px;
  outline: 0;
  font-size: 11px;
  line-height: 1.65;
}

.debug-context {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  padding: 11px;
  background: var(--surface-subtle);
  border-radius: 6px;
}

.debug-context > span {
  grid-column: 1 / -1;
  color: var(--ink-muted);
  font-size: 9px;
  font-weight: 750;
}

.debug-context label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink);
  font-size: 9px;
}

.debug-context input { accent-color: var(--teal); }
.debug-input-panel > .action-button { align-self: end; min-width: 110px; }

.debug-empty {
  display: grid;
  flex: 1;
  place-content: center;
  gap: 6px;
  padding: 40px;
  color: var(--ink-muted);
  background: var(--surface-subtle);
  border: 1px dashed var(--line-strong);
  border-radius: 6px;
  text-align: center;
}

.debug-empty strong { color: var(--ink); font-size: 12px; }
.debug-empty span { font-size: 9px; }

.debug-response {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr);
  gap: 10px;
  padding: 14px;
  background: var(--accent-soft);
  border-radius: 6px;
}

.debug-response img {
  width: 48px;
  height: 54px;
  object-fit: contain;
  object-position: center bottom;
}

.debug-response strong { color: var(--ink-strong); font-size: 11px; }
.debug-response p { margin: 5px 0 0; color: var(--ink); font-size: 11px; line-height: 1.75; }

.debug-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin: 0;
}

.debug-metrics div {
  display: grid;
  gap: 3px;
  padding: 10px;
  background: var(--surface-subtle);
  border-radius: 5px;
}

.debug-metrics dt { color: var(--ink-muted); font-size: 9px; }
.debug-metrics dd { margin: 0; color: var(--ink-strong); font-size: 11px; font-weight: 750; }

.guardrail-result {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid #c9e2dd;
  border-radius: 6px;
}

.guardrail-result strong { color: var(--teal); font-size: 10px; }

.release-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 36px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  padding: 14px;
  background: var(--surface-subtle);
  border: 1px solid var(--line);
  border-radius: 7px;
}

.release-summary > div {
  display: grid;
  gap: 4px;
}

.release-summary span { color: var(--ink-muted); font-size: 9px; }
.release-summary strong { color: var(--ink-strong); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
.release-summary small { color: var(--ink-muted); font-size: 9px; }
.release-summary > i { color: var(--accent); font-size: 18px; font-style: normal; text-align: center; }
.release-summary > i::before { content: "→"; }

.version-list {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 7px;
}

.version-row {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: start;
  padding: 15px;
  border-bottom: 1px solid var(--line);
}

.version-row:last-child { border-bottom: 0; }
.version-row__marker { width: 8px; height: 8px; margin-top: 4px; background: #929ca6; border-radius: 50%; }
.version-row__marker--draft { background: var(--purple); box-shadow: 0 0 0 3px var(--purple-soft); }
.version-row__marker--published { background: var(--teal); box-shadow: 0 0 0 3px var(--teal-soft); }

.version-row__main { display: grid; gap: 5px; }
.version-row__main > div { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; }
.version-row__main strong { color: var(--ink-strong); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; }
.version-row__main p { margin: 0; color: var(--ink); font-size: 10px; line-height: 1.5; }
.version-row__main small { color: var(--ink-muted); font-size: 9px; }

.version-status {
  padding: 2px 5px;
  color: var(--ink-muted);
  background: var(--surface-muted);
  border-radius: 3px;
  font-size: 8px;
  font-weight: 800;
}

.version-status--draft { color: var(--purple); background: var(--purple-soft); }
.version-status--published { color: var(--teal); background: var(--teal-soft); }

.version-row__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  max-width: 220px;
}

.publish-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  padding: 16px;
  place-items: center;
  overflow-y: auto;
  background: rgba(21, 31, 40, 0.5);
}

.publish-dialog {
  display: grid;
  gap: 16px;
  width: min(520px, 100%);
  padding: 20px;
  background: #fff;
  border: 1px solid #cbd3da;
  border-radius: 8px;
  box-shadow: 0 24px 70px rgba(20, 31, 41, 0.28);
}

.publish-dialog > header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 16px;
}

.publish-dialog h2 { margin: 4px 0 0; color: var(--ink-strong); font-size: 20px; }

.dialog-close {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  place-items: center;
  color: var(--ink-muted);
  background: var(--surface-subtle);
  border: 1px solid var(--line);
  border-radius: 5px;
  font-size: 22px;
}

.publish-dialog__impact {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  padding: 12px;
  background: var(--surface-subtle);
  border-radius: 6px;
}

.publish-dialog__impact > div { display: grid; gap: 3px; }
.publish-dialog__impact span { color: var(--ink-muted); font-size: 9px; }
.publish-dialog__impact strong { color: var(--ink-strong); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; }
.publish-dialog__impact i { color: var(--accent); font-style: normal; text-align: center; }

.publish-dialog > label { position: relative; display: grid; gap: 6px; }
.publish-dialog textarea { min-height: 92px; padding-bottom: 24px; }
.publish-dialog > label small { position: absolute; right: 8px; bottom: 7px; color: var(--ink-muted); font-size: 8px; }
.publish-dialog > footer { display: flex; justify-content: flex-end; gap: 8px; }

/* Keep this workspace in the same visual system as the dispute rooms and review desk. */
.agent-console__header {
  position: relative;
  min-height: 154px;
  padding: 26px 30px;
  overflow: hidden;
  align-items: center;
  background: linear-gradient(135deg, #e9f7ff, #f3edff 56%, #fff4dc);
  border: 1px solid #dce7f2;
  border-radius: 34px;
  box-shadow: 0 18px 44px #506c9412;
}

.agent-console__header::after {
  position: absolute;
  top: 17px;
  right: 28px;
  color: #c0aaf1;
  content: "✦";
  font-size: 18px;
  opacity: .55;
  pointer-events: none;
}

.agent-console__title,
.agent-console__header-actions {
  position: relative;
  z-index: 1;
}

.agent-console__title > span,
.agent-detail-header__identity > div > div > span,
.prompt-settings header span,
.publish-dialog header span {
  color: #7185a8;
  font-weight: 900;
  letter-spacing: 0;
}

.agent-console__title h1 {
  margin: 7px 0 8px;
  color: #263754;
  font-size: 40px;
  line-height: 1.08;
}

.agent-console__description {
  max-width: 760px;
  color: #697991;
  font-size: 13px;
  line-height: 1.65;
}

.agent-console__header-actions {
  padding: 12px;
  background: #ffffff80;
  border: 1px solid #ffffffc7;
  border-radius: 20px;
  box-shadow: inset 0 1px 0 #fff, 0 12px 30px #556d950d;
  backdrop-filter: blur(12px);
}

.fleet-status {
  padding: 0 14px 0 4px;
  border-color: #d8e3ef;
}

.fleet-status span i {
  background: #52c790;
  box-shadow: 0 0 0 4px #e7f8ef;
}

.action-button {
  min-height: 44px;
  padding: 10px 16px;
  border-radius: 13px;
  font-weight: 900;
}

.action-button--primary {
  color: #fff;
  background: linear-gradient(135deg, #55b8df, #8585ef);
  border: 0;
  box-shadow: 0 10px 24px #657ab42b;
}

.action-button--primary:hover:not(:disabled) {
  background: linear-gradient(135deg, #49acd5, #7477df);
  border: 0;
  box-shadow: 0 12px 28px #657ab438;
}

.action-button--secondary {
  color: #586b85;
  background: #f8fbff;
  border-color: #d9e4f2;
  box-shadow: 0 8px 18px #5d73a50d;
}

.agent-manager {
  height: clamp(800px, calc(100dvh - 236px), 920px);
  background: #ffffffbf;
  border-color: #dfe8f4;
  border-radius: 30px;
  box-shadow: 0 20px 55px #556d9512;
}

.agent-sidebar {
  min-height: 0;
  padding: 20px 16px;
  overflow-y: auto;
  background: linear-gradient(180deg, #f0f8ff, #fff8f2);
  border-color: #dde8f5;
}

.agent-sidebar__heading {
  padding: 0 4px 14px;
}

.agent-sidebar__heading span {
  color: #34435e;
  font-size: 14px;
}

.agent-sidebar__heading strong {
  width: 32px;
  height: 32px;
  color: #387cad;
  background: #dff1ff;
  border-radius: 13px;
  font-size: 12px;
}

.agent-search input,
.form-grid select,
.prompt-selector select,
.debug-input-panel select,
.threshold-list input {
  min-height: 40px;
  padding: 9px 11px;
  color: #46566f;
  background: #ffffffd9;
  border-color: #dce6f2;
  border-radius: 12px;
  font-size: 12px;
}

.agent-search input:focus,
.form-grid select:focus,
.prompt-selector select:focus,
.debug-input-panel select:focus,
.threshold-list input:focus,
.prompt-editor textarea:focus,
.debug-input-panel textarea:focus,
.publish-dialog textarea:focus {
  border-color: #91a6e8;
  box-shadow: 0 0 0 3px #748be326;
}

.agent-list {
  gap: 9px;
}

.agent-list-item {
  min-height: 76px;
  padding: 10px;
  background: #ffffffc9;
  border-color: transparent;
  border-radius: 18px;
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}

.agent-list-item:hover {
  background: #fff;
  border-color: #d7e6f3;
  box-shadow: 0 12px 28px #536e9017;
  transform: translateY(-2px);
}

.agent-list-item--active {
  background: linear-gradient(135deg, #fff, #f2f8ff 62%, #f5f0ff);
  border-color: #7fc5f4;
  box-shadow: 0 13px 28px #4d86b51c;
  transform: translateX(3px);
}

.agent-list-item__avatar {
  width: 48px;
  height: 54px;
  background: linear-gradient(145deg, #e5faf4, #fff5cf);
  border-color: #d7e7ef;
  border-radius: 15px;
  box-shadow: inset 0 1px 0 #fff;
}

.agent-list-item__avatar img {
  width: 48px;
  height: 50px;
}

.agent-list-item__copy strong {
  color: #34435c;
  font-size: 13px;
}

.agent-list-item__copy small {
  color: #71809a;
  font-size: 11px;
}

.draft-mark,
.agent-detail-header__tags > span,
.editing-state,
.sandbox-label {
  padding: 5px 9px;
  border-radius: 999px;
  font-size: 10px;
}

.draft-mark {
  color: #6e5799;
  background: #f1edff;
  border: 1px solid #ded5f4;
}

.governance-note {
  gap: 5px;
  padding: 14px;
  background: linear-gradient(145deg, #e5faf4, #fff5cf);
  border-color: #c9eee3;
  border-radius: 16px;
  box-shadow: inset 0 1px 0 #fff;
}

.governance-note strong {
  color: #42685e;
  font-size: 12px;
}

.governance-note span {
  color: #71809a;
  font-size: 10px;
}

.agent-workbench {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
  background: linear-gradient(180deg, #ffffffc9, #fbfdffcc);
}

.agent-detail-header {
  min-height: 148px;
  padding: 18px;
  margin: 18px 18px 0;
  background:
    radial-gradient(circle at 20% 15%, #fffffff2, transparent 34%),
    linear-gradient(135deg, #f8fbff, #f4f7ff 62%, #f2fbf7);
  border: 1px solid #dce8f4;
  border-radius: 22px;
  box-shadow: inset 0 1px 0 #ffffffeb;
}

.agent-detail-header__portrait {
  position: relative;
  width: 104px;
  height: 112px;
  background: linear-gradient(145deg, #e3fbf3, #fff5cf);
  border-color: #d7e7ef;
  border-radius: 20px;
  box-shadow: 0 12px 30px #5f72a314, inset 0 1px 0 #fff;
}

.agent-detail-header__portrait img {
  width: 102px;
  height: 106px;
}

.avatar-change-button {
  position: absolute;
  right: 7px;
  bottom: 7px;
  left: 7px;
  min-height: 28px;
  padding: 5px 7px;
  color: #fff;
  background: linear-gradient(135deg, #596fcae8, #60bfa6e8);
  border: 1px solid #ffffff8f;
  border-radius: 10px;
  box-shadow: 0 7px 16px #536c8b2b;
  cursor: pointer;
  font-size: 10px;
  font-weight: 900;
  opacity: .94;
  backdrop-filter: blur(7px);
}

.avatar-change-button:hover {
  background: linear-gradient(135deg, #5066c1, #51ad95);
  opacity: 1;
}

.agent-detail-header h2 {
  color: #34435c;
  font-size: 23px;
}

.agent-detail-header h2 small {
  color: #6279ca;
  font-size: 13px;
}

.agent-detail__summary {
  color: #6f7d92;
  font-size: 12px;
}

.agent-detail-header__tags > span {
  color: #586b8c;
  background: #f1f5ff;
  border-color: #dce5f5;
}

.agent-detail-header__tags .health-label--healthy {
  color: #246149;
  background: #edf8f1;
  border-color: #cfe7d8;
}

.agent-detail-header__tags .health-label--attention {
  color: #9a6a18;
  background: #fff6df;
  border-color: #ead7af;
}

.agent-detail-header__runtime > div {
  padding: 10px 16px;
  background: #ffffff9c;
  border: 1px solid #dfe8f4;
  border-radius: 14px;
  box-shadow: inset 0 1px 0 #fff;
}

.agent-detail-header__runtime strong {
  color: #536683;
  font-size: 12px;
}

.agent-info-profile-card {
  margin: 0;
}

.agent-info-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  min-height: 0;
}

.agent-info-card {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  padding: 18px;
  overflow: hidden;
  background: #ffffffd9;
  border: 1px solid #e0e8f2;
  border-radius: 20px;
  box-shadow: 0 14px 30px #5b74910d;
}

.agent-info-card:nth-child(1) { background: linear-gradient(160deg, #f7fbff, #fff); }
.agent-info-card:nth-child(2) { background: linear-gradient(160deg, #f5f2ff, #fff); }
.agent-info-card:nth-child(3) { background: linear-gradient(160deg, #f0fff8, #fff); }

.agent-info-card > header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e3eaf2;
}

.agent-info-card > header > div {
  display: grid;
  gap: 3px;
}

.agent-info-card > header span,
.agent-info-card > header small {
  color: #7185a8;
  font-size: 9px;
  font-weight: 900;
}

.agent-info-card > header h3 {
  margin: 0;
  color: #34435c;
  font-size: 15px;
}

.agent-info-list {
  display: grid;
  align-content: start;
  gap: 0;
  min-height: 0;
  margin: 0;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.agent-info-list > div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  min-height: 58px;
  padding: 10px 0;
  border-bottom: 1px solid #e5ebf2;
}

.agent-info-list > div:last-child { border-bottom: 0; }
.agent-info-list dt { color: #71809a; font-size: 10px; font-weight: 750; }
.agent-info-list dd { margin: 0; color: #34435c; font-size: 11px; line-height: 1.5; }
.agent-info-list code { color: #586b8c; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 10px; }
.agent-info-list strong { color: #34755a; }

.switch-control {
  min-height: 40px;
  padding: 7px 10px;
  color: #34755a;
  background: #edf8f1;
  border: 1px solid #cfe7d8;
  border-radius: 13px;
  font-size: 11px;
}

.switch-control > span {
  background: #b9c4d0;
}

.switch-control[aria-checked="true"] > span {
  background: linear-gradient(135deg, #64d8a4, #70c7ff);
}

.switch-control--compact {
  min-width: 48px;
  padding: 6px 7px;
  background: transparent;
  border-color: transparent;
}

.agent-tabs {
  display: flex;
  min-height: 58px;
  gap: 6px;
  padding: 8px 12px;
  margin: 14px 18px 0;
  background: linear-gradient(135deg, #f8fbff, #f6f3ff);
  border: 1px solid #dfe8f4;
  border-radius: 18px;
}

.agent-tabs button {
  flex: 1 1 0;
  min-height: 42px;
  color: #687a96;
  border: 1px solid transparent;
  border-radius: 14px;
  font-size: 12px;
}

.agent-tabs button::after {
  content: none;
}

.agent-tabs .agent-tabs__item--active {
  color: #526bc1;
  background: #fff;
  border-color: #dce6f4;
  box-shadow: 0 8px 20px #5d73a514;
}

.agent-tab-panel {
  min-height: 0;
  gap: 18px;
  padding: 20px 18px 24px;
  align-content: start;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.panel-toolbar h3 {
  color: #34435c;
  font-size: 17px;
}

.panel-toolbar p {
  color: #71809a;
  font-size: 12px;
}

.range-control {
  padding: 3px;
  background: #eef3ff;
  border: 1px solid #dfe7f4;
  border-radius: 14px;
}

.range-control button {
  min-height: 34px;
  padding: 6px 12px;
  border-radius: 11px;
  font-size: 11px;
}

.range-control .range-control__item--active {
  color: #526bc1;
  background: #fff;
  box-shadow: 0 8px 20px #5d73a514;
}

.metric-strip {
  gap: 12px;
  overflow: visible;
  background: transparent;
  border: 0;
  border-radius: 0;
}

.metric-cell {
  min-height: 112px;
  padding: 16px;
  background: linear-gradient(160deg, #fff, #f6faff);
  border: 1px solid #dfe8f4;
  border-radius: 18px;
  box-shadow: 0 12px 28px #536e900d;
}

.metric-cell:nth-child(2) { background: linear-gradient(160deg, #f1fff8, #fff); }
.metric-cell:nth-child(3) { background: linear-gradient(160deg, #fff2f5, #fff); }
.metric-cell:nth-child(4) { background: linear-gradient(160deg, #fff8e4, #fff); }
.metric-cell:nth-child(5) { background: linear-gradient(160deg, #f4f0ff, #fff); }
.metric-cell dt { color: #71809a; font-size: 11px; }
.metric-cell dd strong { color: #263754; font-size: 26px; }
.metric-cell dd span,
.metric-cell small { font-size: 10px; }

.overview-content {
  gap: 16px;
}

.overview-section,
.prompt-editor,
.prompt-settings,
.strategy-section,
.debug-input-panel,
.debug-output-panel {
  padding: 18px;
  background: #ffffffd9;
  border-color: #e0e8f2;
  border-radius: 20px;
  box-shadow: 0 14px 30px #5b74910d;
}

.overview-section__header h3,
.strategy-section > header h3,
.debug-input-panel > header h3,
.debug-output-panel > header h3,
.prompt-editor > header h3,
.prompt-settings section > header h3 {
  color: #34435c;
  font-size: 15px;
}

.overview-section__header p {
  color: #7b8692;
  font-size: 11px;
}

.legend-dot--resolved,
.trend-bar--resolved { background: #52c790; }
.legend-dot--handoff,
.trend-bar--handoff { background: #f5b84d; }
.legend-dot--failed,
.trend-bar--failed { background: #ed6e7f; }

.trend-chart {
  border-color: #dfe8f4;
  background-image: linear-gradient(to bottom, transparent 24%, #edf2f7 25%, transparent 26%, transparent 49%, #edf2f7 50%, transparent 51%, transparent 74%, #edf2f7 75%, transparent 76%);
}

.trend-bar {
  border-radius: 5px 5px 2px 2px;
}

.quality-track {
  height: 8px;
  background: #eef3f8;
  border-radius: 999px;
}

.quality-track i {
  background: linear-gradient(90deg, #7e93e5, #69c2a4);
}

.quality-summary {
  padding: 14px;
  background: linear-gradient(145deg, #e5faf4, #eef7ff);
  border: 1px solid #cfe9e2;
  border-radius: 14px;
}

.run-list {
  border-color: #dfe8f4;
  border-radius: 16px;
}

.run-row {
  min-height: 46px;
  padding: 9px 13px;
  border-color: #e5ebf2;
  font-size: 11px;
}

.run-row--header {
  color: #71809a;
  background: linear-gradient(135deg, #f8fbff, #f6f3ff);
}

.prompt-selector,
.release-summary {
  padding: 14px 16px;
  background:
    radial-gradient(circle at 12% 0, #fffffff0, transparent 34%),
    linear-gradient(135deg, #f8fbff, #f4f8ff 52%, #f2fbf7);
  border-color: #dce8f4;
  border-radius: 18px;
  box-shadow: inset 0 1px 0 #fff;
}

.prompt-editor {
  padding: 0;
  overflow: hidden;
}

.prompt-editor > header {
  background: linear-gradient(135deg, #f8fbff, #f6f3ff);
}

.prompt-editor textarea {
  color: #34435c;
  background: #ffffffd6;
  font-size: 12px;
}

.prompt-editor > footer {
  background: #f8fbff;
}

.prompt-settings {
  padding: 0;
  overflow: hidden;
}

.prompt-settings section:nth-child(1) { background: linear-gradient(160deg, #fff, #f7faff); }
.prompt-settings section:nth-child(2) { background: linear-gradient(160deg, #f4fbff, #fff); }
.prompt-settings section:nth-child(3) { background: linear-gradient(160deg, #f4fff9, #fff); }

.variable-list code,
.authority-block code {
  padding: 5px 7px;
  color: #586b8c;
  background: #eef5ff;
  border-color: #d5e3f2;
  border-radius: 8px;
  font-size: 9px;
}

.sticky-action-bar {
  padding: 13px 14px;
  background: #ffffffeb;
  border-color: #d9e4f1;
  border-radius: 16px;
  box-shadow: 0 14px 34px #536c8b1c;
}

.strategy-section:nth-child(1) { background: linear-gradient(160deg, #f7fbff, #fff); }
.strategy-section:nth-child(2) { background: linear-gradient(160deg, #fffaf0, #fff); }
.strategy-section:nth-child(3) { background: linear-gradient(160deg, #f5f2ff, #fff); }
.strategy-section:nth-child(4) { background: linear-gradient(160deg, #f0fff8, #fff); }

.switch-row {
  border-color: #e3eaf2;
}

.switch-row strong {
  color: #34435c;
  font-size: 12px;
}

.switch-row p {
  color: #71809a;
  font-size: 10px;
}

.switch-row--mode {
  grid-template-columns: minmax(0, 1fr) minmax(250px, 320px);
}

.thinking-mode-control {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 4px;
  padding: 4px;
  background: linear-gradient(135deg, #eef5ff, #f4efff);
  border: 1px solid #dce6f4;
  border-radius: 14px;
}

.thinking-mode-control button {
  min-height: 38px;
  padding: 7px 10px;
  color: #71809a;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  font-size: 11px;
  font-weight: 800;
}

.thinking-mode-control .thinking-mode-control__item--active {
  color: #526bc1;
  background: #fff;
  border-color: #dce6f4;
  box-shadow: 0 7px 18px #5d73a51a;
}

.threshold-list label > div {
  background: #ffffffc9;
  border-color: #dce6f2;
  border-radius: 12px;
}

.debug-input-panel {
  background: linear-gradient(160deg, #f7fbff, #fff 42%);
}

.debug-output-panel {
  background: linear-gradient(160deg, #f5f2ff, #fff 42%);
}

.debug-input-panel textarea,
.publish-dialog textarea {
  background: #ffffffd9;
  border-color: #dce6f2;
  border-radius: 14px;
  font-size: 12px;
}

.debug-context,
.debug-metrics div {
  background: #f8fbff;
  border: 1px solid #e0e8f2;
  border-radius: 12px;
}

.debug-empty {
  background: linear-gradient(135deg, #f8fbff, #f6f3ff);
  border-color: #cfdceb;
  border-radius: 16px;
}

.debug-response {
  background: linear-gradient(135deg, #eaf7ff, #f4eeff);
  border: 1px solid #d8e2f2;
  border-radius: 16px;
}

.guardrail-result {
  background: linear-gradient(145deg, #e5faf4, #fff);
  border-color: #c9eee3;
  border-radius: 14px;
}

.release-summary > i {
  color: #7e93e5;
}

.version-list {
  background: #ffffffd9;
  border-color: #dfe8f4;
  border-radius: 18px;
  box-shadow: 0 14px 30px #5b74910d;
}

.version-row {
  padding: 17px;
  border-color: #e4ebf2;
}

.version-row:nth-child(even) {
  background: #f8fbff99;
}

.version-status {
  padding: 4px 8px;
  border-radius: 999px;
  font-size: 9px;
}

.publish-dialog-backdrop {
  background: #26354d70;
  backdrop-filter: blur(8px);
}

.publish-dialog {
  padding: 22px;
  background: linear-gradient(145deg, #fff, #f7faff);
  border-color: #dce6f2;
  border-radius: 24px;
  box-shadow: 0 28px 80px #22304740;
}

.dialog-close {
  color: #65738a;
  background: #f2f6fb;
  border-color: #dbe4ef;
  border-radius: 13px;
}

.publish-dialog__impact {
  background: linear-gradient(135deg, #eef6ff, #f4efff);
  border: 1px solid #dce6f4;
  border-radius: 16px;
}

.avatar-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  padding: 16px;
  place-items: center;
  overflow-y: auto;
  background: #26354d70;
  backdrop-filter: blur(8px);
}

.avatar-dialog {
  display: grid;
  gap: 18px;
  width: min(760px, 100%);
  padding: 22px;
  background: linear-gradient(145deg, #fff, #f7faff);
  border: 1px solid #dce6f2;
  border-radius: 24px;
  box-shadow: 0 28px 80px #22304740;
}

.avatar-dialog > header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 18px;
}

.avatar-dialog > header > div {
  display: grid;
  gap: 4px;
}

.avatar-dialog > header span {
  color: #7185a8;
  font-size: 10px;
  font-weight: 900;
}

.avatar-dialog h2 {
  margin: 0;
  color: #34435c;
  font-size: 22px;
}

.avatar-dialog header p {
  margin: 0;
  color: #71809a;
  font-size: 12px;
}

.avatar-dialog__content {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 18px;
  align-items: stretch;
}

.avatar-dialog__preview {
  display: grid;
  align-content: start;
  justify-items: center;
  gap: 8px;
  padding: 16px;
  background: linear-gradient(145deg, #e5faf4, #fff5cf);
  border: 1px solid #c9eee3;
  border-radius: 20px;
  text-align: center;
}

.avatar-dialog__preview > span {
  justify-self: start;
  color: #71809a;
  font-size: 10px;
  font-weight: 800;
}

.avatar-dialog__preview > div {
  display: grid;
  width: 140px;
  height: 154px;
  place-items: end center;
  overflow: hidden;
  background: #ffffffb8;
  border: 1px solid #ffffffd9;
  border-radius: 22px;
  box-shadow: inset 0 1px 0 #fff, 0 12px 28px #536e9012;
}

.avatar-dialog__preview img {
  width: 138px;
  height: 148px;
  object-fit: contain;
  object-position: center bottom;
}

.avatar-dialog__preview strong {
  color: #34435c;
  font-size: 13px;
}

.avatar-dialog__preview small {
  color: #71809a;
  font-size: 9px;
}

.avatar-dialog__picker {
  display: grid;
  align-content: start;
  gap: 12px;
  padding: 16px;
  background: #ffffffd9;
  border: 1px solid #dfe8f4;
  border-radius: 20px;
}

.avatar-dialog__picker > strong {
  color: #34435c;
  font-size: 13px;
}

.avatar-preset-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 9px;
}

.avatar-preset-grid button {
  display: grid;
  min-height: 112px;
  padding: 8px;
  place-items: end center;
  color: #687a96;
  background: linear-gradient(145deg, #f8fbff, #f6f3ff);
  border: 1px solid #dfe8f4;
  border-radius: 15px;
  cursor: pointer;
  font-size: 10px;
  font-weight: 800;
}

.avatar-preset-grid button:hover {
  border-color: #a8c8e7;
  box-shadow: 0 9px 20px #5d73a514;
  transform: translateY(-2px);
}

.avatar-preset-grid .avatar-preset--active {
  color: #526bc1;
  background: linear-gradient(145deg, #eaf7ff, #f4eeff);
  border-color: #7fc5f4;
  box-shadow: 0 10px 24px #4d86b51c;
}

.avatar-preset-grid img {
  width: 64px;
  height: 72px;
  object-fit: contain;
  object-position: center bottom;
}

.avatar-upload-button {
  position: relative;
  display: grid;
  gap: 3px;
  min-height: 58px;
  padding: 11px 14px;
  align-content: center;
  color: #586b91;
  background: linear-gradient(135deg, #eef6ff, #f4efff);
  border: 1px dashed #aec6e2;
  border-radius: 14px;
  cursor: pointer;
}

.avatar-upload-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  opacity: 0;
}

.avatar-upload-button span {
  font-size: 11px;
  font-weight: 900;
}

.avatar-upload-button small {
  color: #8190a7;
  font-size: 9px;
}

.avatar-dialog > footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.detail-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  padding: 16px;
  place-items: center;
  overflow-y: auto;
  background: #26354d70;
  backdrop-filter: blur(8px);
}

.detail-dialog {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 16px;
  width: min(840px, 100%);
  max-height: min(760px, calc(100dvh - 32px));
  padding: 22px;
  overflow: hidden;
  color: #34435c;
  background: linear-gradient(145deg, #fff, #f7faff);
  border: 1px solid #dce6f2;
  border-radius: 24px;
  box-shadow: 0 28px 80px #22304740;
}

.detail-dialog > header,
.detail-dialog > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.detail-dialog > header {
  align-items: start;
}

.detail-dialog > header > div {
  display: grid;
  gap: 4px;
}

.detail-dialog > header span,
.detail-dialog__body > :where(div, ul) > span {
  color: #7185a8;
  font-size: 10px;
  font-weight: 900;
}

.detail-dialog h2 {
  margin: 0;
  color: #34435c;
  font-size: 22px;
}

.detail-dialog header p,
.detail-dialog > footer > span {
  margin: 0;
  color: #71809a;
  font-size: 11px;
  line-height: 1.5;
}

.detail-dialog__body {
  min-height: 0;
  padding: 2px;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.detail-run-table {
  min-width: 620px;
  overflow: hidden;
  background: #fff;
  border: 1px solid #dfe8f4;
  border-radius: 16px;
}

.detail-run-row {
  display: grid;
  grid-template-columns: 110px 100px minmax(0, 1fr) 90px;
  gap: 14px;
  align-items: center;
  min-height: 54px;
  padding: 10px 14px;
  border-bottom: 1px solid #e4ebf2;
  font-size: 11px;
}

.detail-run-row:last-child { border-bottom: 0; }
.detail-run-row--header { min-height: 42px; color: #71809a; background: #f5f8ff; font-weight: 800; }
.detail-run-row strong { color: #526bc1; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.detail-run-row time { color: #71809a; }
.detail-run-row em { color: #9a6a18; font-style: normal; font-weight: 800; }

.detail-prompt-editor {
  display: grid;
  grid-template-rows: auto minmax(420px, 1fr);
  min-height: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid #dfe8f4;
  border-radius: 18px;
}

.detail-prompt-editor > div {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 16px;
  color: #71809a;
  background: linear-gradient(135deg, #f8fbff, #f6f3ff);
  border-bottom: 1px solid #dfe8f4;
  font-size: 10px;
}

.detail-prompt-editor > div strong { color: #526bc1; }

.detail-prompt-editor textarea {
  width: 100%;
  min-height: 420px;
  padding: 18px;
  resize: none;
  color: #34435c;
  background: #ffffffd9;
  border: 0;
  outline: 0;
  font-family: ui-monospace, SFMono-Regular, Consolas, "Microsoft YaHei", monospace;
  font-size: 12px;
  line-height: 1.75;
}

.detail-token-grid,
.detail-profile-grid section > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.detail-token-grid code,
.detail-profile-grid code {
  padding: 8px 10px;
  color: #586b8c;
  background: #eef5ff;
  border: 1px solid #d5e3f2;
  border-radius: 10px;
  font-size: 10px;
}

.detail-rule-list,
.detail-profile-grid ul {
  display: grid;
  gap: 10px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.detail-rule-list li {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
  padding: 14px;
  background: linear-gradient(145deg, #e5faf4, #fff);
  border: 1px solid #c9eee3;
  border-radius: 14px;
}

.detail-rule-list strong { color: #34755a; font-size: 10px; }
.detail-rule-list span { color: #586b85; font-size: 11px; line-height: 1.6; }

.detail-profile-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.detail-profile-grid section,
.detail-version-grid section {
  display: grid;
  align-content: start;
  gap: 9px;
  padding: 15px;
  background: #fff;
  border: 1px solid #dfe8f4;
  border-radius: 15px;
}

.detail-profile-grid section > span,
.detail-version-grid section > span {
  color: #71809a;
  font-size: 10px;
  font-weight: 800;
}

.detail-profile-grid__wide,
.detail-version-grid__summary {
  grid-column: 1 / -1;
}

.detail-profile-grid li {
  position: relative;
  padding-left: 14px;
  color: #71809a;
  font-size: 10px;
  line-height: 1.6;
}

.detail-profile-grid li::before {
  position: absolute;
  top: .65em;
  left: 2px;
  width: 5px;
  height: 5px;
  content: "";
  background: #ed6e7f;
  border-radius: 50%;
}

.detail-debug-result {
  display: grid;
  gap: 14px;
}

.detail-version-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.detail-version-grid strong { color: #34435c; font-size: 13px; }
.detail-version-grid small { color: #71809a; font-size: 10px; }
.detail-version-grid p { margin: 0; color: #586b85; font-size: 11px; line-height: 1.65; }

.card-header-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.card-header-actions .text-button {
  flex: 0 0 auto;
  font-size: 10px;
}

.authority-scroll,
.debug-result-scroll {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.debug-result-scroll {
  display: grid;
  align-content: start;
  gap: 14px;
}

@media (min-width: 961px) {
  .agent-tab-panel {
    height: 100%;
    align-content: stretch;
    overflow: hidden;
    scrollbar-gutter: auto;
  }

  .agent-tab-panel--info {
    grid-template-rows: auto 220px minmax(0, 1fr);
  }

  .agent-tab-panel--info .agent-info-profile-card {
    min-height: 220px;
    height: 220px;
  }

  .agent-tab-panel--info .agent-info-grid,
  .agent-tab-panel--info .agent-info-card {
    min-height: 0;
    height: 100%;
  }

  .agent-tab-panel--overview {
    grid-template-rows: auto 92px minmax(0, 1fr);
  }

  .agent-tab-panel--overview .metric-strip,
  .agent-tab-panel--overview .metric-cell {
    height: 92px;
    min-height: 92px;
  }

  .agent-tab-panel--overview .metric-cell {
    align-content: center;
    padding: 12px 14px;
  }

  .agent-tab-panel--overview .metric-cell dd strong {
    font-size: 23px;
  }

  .overview-content {
    grid-template-columns: minmax(0, 1.55fr) minmax(260px, .9fr);
    grid-template-rows: minmax(0, 1fr) 164px;
    min-height: 0;
    height: 100%;
    gap: 14px;
  }

  .overview-section {
    min-height: 0;
    height: 100%;
  }

  .overview-section--trend {
    display: grid;
    grid-column: 1;
    grid-row: 1;
    grid-template-rows: auto minmax(0, 1fr);
  }

  .overview-section--trend .trend-chart {
    min-height: 0;
    height: auto;
  }

  .overview-section--quality {
    display: grid;
    grid-column: 2;
    grid-row: 1 / span 2;
    grid-template-rows: auto minmax(0, 1fr) auto;
  }

  .overview-section--quality .quality-list {
    min-height: 0;
    padding: 4px 2px 12px 0;
    overflow-y: auto;
    scrollbar-gutter: stable;
  }

  .overview-section--runs {
    display: grid;
    grid-column: 1;
    grid-row: 2;
    grid-template-rows: auto minmax(0, 1fr);
  }

  .overview-section--runs .overview-section__header {
    margin-bottom: 9px;
  }

  .overview-section--runs .run-list {
    min-height: 0;
    overflow: auto;
    scrollbar-gutter: stable;
  }

  .agent-tab-panel--prompt {
    grid-template-rows: auto 72px minmax(0, 1fr) 66px;
  }

  .agent-tab-panel--prompt .prompt-selector {
    height: 72px;
    padding: 10px 14px;
  }

  .agent-tab-panel--prompt .prompt-workspace {
    grid-template-columns: minmax(0, 1fr) minmax(270px, 310px);
    min-height: 0;
    height: 100%;
    align-items: stretch;
  }

  .agent-tab-panel--prompt .prompt-editor {
    grid-template-rows: auto minmax(0, 1fr) auto;
    min-height: 0;
    height: 100%;
  }

  .agent-tab-panel--prompt .prompt-editor textarea {
    min-height: 0;
    height: 100%;
    resize: none;
    overflow-y: auto;
  }

  .agent-tab-panel--prompt .prompt-settings {
    min-height: 0;
    height: 100%;
    overflow-y: auto;
    scrollbar-gutter: stable;
  }

  .agent-tab-panel--prompt .sticky-action-bar {
    position: static;
    min-height: 66px;
    height: 66px;
  }

  .agent-tab-panel--strategy {
    grid-template-rows: auto minmax(0, 1fr);
  }

  .agent-tab-panel--strategy .strategy-layout {
    grid-template-columns: minmax(0, 1fr) minmax(280px, .42fr);
    min-height: 0;
    height: 100%;
    align-items: stretch;
  }

  .agent-tab-panel--strategy .capability-groups {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: repeat(2, minmax(0, 1fr));
    min-height: 0;
    height: 100%;
  }

  .agent-tab-panel--strategy .strategy-aside {
    grid-template-rows: 224px minmax(0, 1fr);
    min-height: 0;
    height: 100%;
  }

  .agent-tab-panel--strategy .strategy-section {
    display: grid;
    grid-template-rows: auto minmax(0, 1fr);
    min-height: 0;
    height: 100%;
  }

  .agent-tab-panel--strategy .switch-list,
  .agent-tab-panel--strategy .threshold-list,
  .agent-tab-panel--strategy .authority-scroll {
    min-height: 0;
    align-content: start;
    overflow-y: auto;
    overscroll-behavior: contain;
    scrollbar-gutter: stable;
  }

  .agent-tab-panel--strategy .switch-row {
    min-height: 58px;
    padding: 9px 0;
  }

  .agent-tab-panel--strategy .switch-row--mode {
    grid-template-columns: 1fr;
    gap: 8px;
    min-height: 118px;
  }

  .agent-tab-panel--strategy .thinking-mode-control {
    width: 100%;
    min-width: 0;
  }

  .agent-tab-panel--strategy .threshold-list {
    align-content: start;
    padding-right: 2px;
  }

  .agent-tab-panel--debug {
    grid-template-rows: auto minmax(0, 1fr);
  }

  .agent-tab-panel--debug .debug-layout {
    min-height: 0;
    height: 100%;
    align-items: stretch;
  }

  .agent-tab-panel--debug .debug-input-panel,
  .agent-tab-panel--debug .debug-output-panel {
    min-height: 0;
    height: 100%;
    overflow: hidden;
  }

  .agent-tab-panel--debug .debug-message-field {
    grid-template-rows: auto minmax(0, 1fr);
    min-height: 0;
    flex: 1 1 auto;
  }

  .agent-tab-panel--debug .debug-message-field textarea {
    min-height: 0;
    height: 100%;
    resize: none;
  }

  .agent-tab-panel--debug .debug-empty,
  .agent-tab-panel--debug .debug-result-scroll {
    min-height: 0;
    flex: 1 1 auto;
  }

  .agent-tab-panel--versions {
    grid-template-rows: auto 92px minmax(0, 1fr);
  }

  .agent-tab-panel--versions .release-summary {
    min-height: 92px;
    height: 92px;
  }

  .agent-tab-panel--versions .version-list {
    min-height: 0;
    height: 100%;
    overflow-y: auto;
    scrollbar-gutter: stable;
  }

  .agent-tab-panel--versions .version-row {
    min-height: 116px;
    height: 116px;
    overflow: hidden;
  }

  .agent-tab-panel--versions .version-row__main p {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
}

@media (max-width: 1180px) {
  .fleet-status { display: none; }
}

@media (max-width: 960px) {
  .agent-console__header { align-items: start; }
  .agent-console__header-actions { flex-wrap: wrap; }
  .agent-manager { grid-template-columns: 1fr; height: auto; }
  .agent-sidebar { overflow: visible; border-right: 0; border-bottom: 1px solid var(--line); }
  .agent-workbench { display: block; overflow: visible; }
  .agent-tab-panel { overflow: visible; scrollbar-gutter: auto; }
  .overview-content,
  .prompt-workspace,
  .strategy-layout { grid-template-columns: 1fr; }
  .agent-info-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .agent-info-card--governance { grid-column: 1 / -1; }
  .capability-groups { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .authority-scroll,
  .debug-result-scroll { overflow: visible; scrollbar-gutter: auto; }
  .agent-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .governance-note { margin-top: 12px; }
  .agent-detail-header { align-items: start; }
  .agent-detail-header__runtime { display: grid; justify-items: end; }
  .agent-detail-header__runtime > div { padding: 10px 12px; border: 1px solid var(--line); text-align: right; }
  .metric-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .metric-cell,
  .metric-cell:nth-child(3),
  .metric-cell:nth-child(n + 4) { border: 1px solid var(--line); }
  .debug-layout { grid-template-columns: 1fr; }
  .debug-input-panel,
  .debug-output-panel { min-height: auto; }
}

@media (max-width: 700px) {
  .agent-console__header { display: grid; }
  .agent-console__header-actions { justify-content: start; }
  .agent-detail-header { display: grid; }
  .agent-detail-header__runtime { display: flex; justify-items: initial; justify-content: space-between; width: 100%; }
  .agent-detail-header__runtime > div { padding: 10px 12px; border: 1px solid var(--line); text-align: left; }
  .agent-tabs { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 8px; margin: 12px 14px 0; }
  .agent-tab-panel { padding: 16px 14px 20px; }
  .panel-toolbar { align-items: start; }
  .panel-toolbar:not(.panel-toolbar--prompt) { display: grid; }
  .metric-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric-cell:nth-child(2),
  .metric-cell:nth-child(3),
  .metric-cell:nth-child(4),
  .metric-cell:nth-child(5) { border: 1px solid var(--line); }
  .metric-cell:nth-child(5) { grid-column: 1 / -1; }
  .overview-section--trend { overflow: hidden; }
  .trend-chart { gap: 4px; padding-right: 0; padding-left: 0; }
  .chart-legend { justify-content: start; }
  .prompt-selector { grid-template-columns: 1fr; }
  .prompt-selector dl { grid-template-columns: 1fr 1fr; }
  .prompt-selector dl div:first-child { grid-column: 1 / -1; }
  .prompt-settings { grid-template-columns: 1fr; }
  .agent-info-grid { grid-template-columns: 1fr; }
  .agent-info-card--governance { grid-column: auto; }
  .capability-groups { grid-template-columns: 1fr; }
  .prompt-settings section,
  .prompt-settings section:nth-child(2) { border-right: 0; }
  .prompt-settings section:last-child { grid-column: auto; }
  .sticky-action-bar { position: static; grid-template-columns: 1fr 1fr; }
  .sticky-action-bar > div { grid-column: 1 / -1; }
  .run-row { grid-template-columns: 84px 72px minmax(0, 1fr); }
  .run-row > :last-child { grid-column: 3; }
  .run-row--header > :last-child { display: none; }
  .version-row { grid-template-columns: 12px minmax(0, 1fr); }
  .version-row__actions { grid-column: 2; justify-content: flex-start; max-width: none; }
  .switch-row--mode { grid-template-columns: 1fr; }
  .thinking-mode-control { width: 100%; }
  .avatar-dialog__content { grid-template-columns: 1fr; }
  .avatar-dialog__preview {
    grid-template-columns: 120px minmax(0, 1fr);
    justify-items: start;
    text-align: left;
  }
  .avatar-dialog__preview > span { grid-column: 1 / -1; }
  .avatar-dialog__preview > div { grid-row: span 3; width: 112px; height: 120px; }
  .avatar-dialog__preview img { width: 108px; height: 116px; }
  .avatar-preset-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .detail-profile-grid,
  .detail-version-grid { grid-template-columns: 1fr; }
  .detail-profile-grid__wide,
  .detail-version-grid__summary { grid-column: auto; }
}

@media (max-width: 520px) {
  .agent-console { gap: 12px; }
  .agent-console__header {
    min-height: 0;
    padding: 22px 18px;
    border-radius: 26px;
  }
  .agent-console__header::after { display: none; }
  .agent-console__title h1 { font-size: 28px; }
  .agent-console__header-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
    width: 100%;
    padding: 8px;
    border-radius: 16px;
  }
  .agent-console__header-actions .action-button { min-height: 44px; }
  .agent-sidebar { padding: 15px 11px; }
  .agent-sidebar__heading {
    display: grid;
    grid-template-columns: 1fr auto;
  }
  .mobile-agent-toggle {
    display: block;
    grid-column: 1 / -1;
    width: 100%;
    min-height: 40px;
    padding: 8px 10px;
    color: var(--accent);
    text-align: left;
    background: var(--surface);
    border: 1px solid var(--line-strong);
    border-radius: 12px;
    font-size: 11px;
    font-weight: 750;
  }
  .agent-picker { display: none; }
  .agent-picker--open { display: block; }
  .agent-picker--open .agent-search { padding-top: 10px; }
  .agent-list { grid-template-columns: 1fr; }
  .agent-detail-header { padding: 14px; margin: 12px 12px 0; border-radius: 18px; }
  .agent-info-profile-card { margin: 0; }
  .agent-detail-header__identity { align-items: start; }
  .agent-detail-header__portrait { width: 68px; height: 78px; }
  .agent-detail-header__portrait img { width: 68px; height: 74px; }
  .avatar-change-button {
    right: 4px;
    bottom: 4px;
    left: 4px;
    min-height: 23px;
    padding: 3px 4px;
    border-radius: 8px;
    font-size: 8px;
  }
  .agent-detail-header h2 { font-size: 18px; }
  .agent-detail-header__runtime { align-items: center; }
  .agent-detail-header__runtime > div { min-width: 0; }
  .agent-tabs { margin: 10px 12px 0; border-radius: 16px; }
  .agent-tabs button { min-height: 44px; padding: 7px 4px; font-size: 11px; }
  .panel-toolbar,
  .panel-toolbar--prompt { display: grid; }
  .range-control { width: 100%; }
  .metric-strip { grid-template-columns: 1fr; }
  .metric-cell,
  .metric-cell:nth-child(2),
  .metric-cell:nth-child(3),
  .metric-cell:nth-child(4),
  .metric-cell:nth-child(5) {
    grid-column: auto;
    min-height: 84px;
    border: 1px solid var(--line);
  }
  .overview-section,
  .prompt-editor,
  .prompt-settings,
  .strategy-section,
  .debug-input-panel,
  .debug-output-panel { padding: 13px; }
  .overview-section__header { display: grid; }
  .trend-chart { height: 180px; }
  .run-row,
  .run-row--header { grid-template-columns: 1fr; gap: 4px; padding: 10px; }
  .run-row--header { display: none; }
  .run-row > :last-child { grid-column: auto; }
  .prompt-selector dl { grid-template-columns: 1fr; }
  .prompt-selector dl div:first-child { grid-column: auto; }
  .prompt-editor { grid-template-rows: auto minmax(380px, 1fr) auto; padding: 0; }
  .prompt-editor textarea { min-height: 380px; }
  .form-grid,
  .debug-context,
  .debug-metrics { grid-template-columns: 1fr; }
  .form-grid label:first-child,
  .debug-context > span { grid-column: auto; }
  .sticky-action-bar { grid-template-columns: 1fr; }
  .sticky-action-bar > div { grid-column: auto; }
  .sticky-action-bar .action-button { min-height: 44px; }
  .switch-row { min-height: 78px; }
  .release-summary { grid-template-columns: 1fr; }
  .release-summary > i { transform: rotate(90deg); }
  .publish-dialog { padding: 16px; border-radius: 20px; }
  .publish-dialog__impact { grid-template-columns: 1fr; }
  .publish-dialog__impact > i { transform: rotate(90deg); }
  .publish-dialog > footer { display: grid; grid-template-columns: 1fr; }
  .publish-dialog .action-button { min-height: 44px; }
  .avatar-dialog { padding: 16px; border-radius: 20px; }
  .avatar-dialog h2 { font-size: 19px; }
  .avatar-dialog__preview { grid-template-columns: 92px minmax(0, 1fr); padding: 12px; border-radius: 16px; }
  .avatar-dialog__preview > div { width: 86px; height: 96px; border-radius: 15px; }
  .avatar-dialog__preview img { width: 84px; height: 92px; }
  .avatar-dialog__picker { padding: 12px; border-radius: 16px; }
  .avatar-preset-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .avatar-preset-grid button { min-height: 104px; }
  .avatar-dialog > footer { display: grid; grid-template-columns: 1fr; }
  .avatar-dialog .action-button { min-height: 44px; }
  .detail-dialog { padding: 16px; border-radius: 20px; }
  .detail-dialog h2 { font-size: 19px; }
  .detail-dialog > footer { display: grid; grid-template-columns: 1fr; }
  .detail-dialog > footer .action-button { width: 100%; }
  .detail-rule-list li { grid-template-columns: 1fr; }
  .detail-prompt-editor { grid-template-rows: auto minmax(360px, 1fr); }
  .detail-prompt-editor textarea { min-height: 360px; }
}

@media (prefers-reduced-motion: reduce) {
  .switch-control > span,
  .switch-control > span::after { transition: none; }
}
</style>
