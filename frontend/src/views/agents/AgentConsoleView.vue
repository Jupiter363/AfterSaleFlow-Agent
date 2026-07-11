<script setup>
const agents = [
  {
    icon: "🎙️",
    name: "争议接待官",
    role: "Intake Officer",
    summary: "识别争议、抽取订单/售后/物流引用，并给出风险等级。",
    status: "运行中",
  },
  {
    icon: "📚",
    name: "证据书记官",
    role: "Evidence Clerk",
    summary: "校验证据合法性与完整性，把双方材料归档到证据库。",
    status: "运行中",
  },
  {
    icon: "⚖️",
    name: "AI 主审官",
    role: "AI Judge",
    summary: "组织三轮庭审陈述、沉淀争点，并在第三轮后生成裁决方案草案。",
    status: "运行中",
  },
  {
    icon: "💬",
    name: "AI 评审团",
    role: "Deliberation Panel",
    summary: "对最终草案做事实、规则、风险与公平性复核。",
    status: "预留配置",
    active: true,
  },
  {
    icon: "🧾",
    name: "审核解释官",
    role: "Review Copilot",
    summary: "向平台审核员转述争点、证据、草案与执行建议。",
    status: "运行中",
  },
];

const configTabs = [
  ["身份档案", "数字人名称、角色口吻、可见对象和状态表情。"],
  ["Prompt 版本", "系统提示词、用户提示词和变量模板的版本治理。"],
  ["Skill 权限", "每个 Agent 只能访问授权技能，不能越权执行补救。"],
  ["运行策略", "介入模式、评分阈值、重生成次数和降级方式。"],
];

const strategies = [
  {
    code: "FINAL_ONLY",
    title: "默认最终介入",
    tag: "推荐",
    nodes: [
      ["第 1 轮陈述", "blue"],
      ["第 2 轮陈述", "yellow"],
      ["第 3 轮陈述", "blue"],
      ["最终方案 → 评审团", "purple"],
      ["通过后送平台终审", "green"],
    ],
    description:
      "固定三轮陈述后生成确定的裁决方案草案。评审团只在最终方案后评分，低于 80 分才要求法官最多重生成 2 次。",
  },
  {
    code: "THREE_ROUND",
    title: "三轮后终评",
    nodes: [
      ["第 1 轮只提问", "blue"],
      ["第 2 轮只归纳", "yellow"],
      ["第 3 轮定方案", "green"],
      ["评审团终评", "purple"],
    ],
    description:
      "不再每轮打断庭审。前三轮用于双方解释和法官归纳，第三轮后必须收敛为可执行方案，再交评审团复核。",
  },
  {
    code: "RISK_ADAPTIVE",
    title: "风险自适应",
    nodes: [
      ["LOW：跳过", "green"],
      ["MEDIUM：最终介入", "yellow"],
      ["HIGH：最终强制评审", "purple"],
      ["管理员可覆盖", "blue"],
    ],
    description:
      "风险等级仍影响是否触发评审团，但触发点统一放在最终方案之后。高风险强制终评，低风险可跳过评审团但不可跳过人审。",
  },
];
</script>

<template>
  <section class="agent-console" data-agent-console>
    <header class="agent-console__intro">
      <div>
        <span>AGENT PARK CONTROL</span>
        <h1>数字人管理中心</h1>
        <p>
          给每个数字人安排自己的小屋、技能和出场规则。这个模块先进入代码库沉淀统一 UI，
          后期接入真实配置后端，不抢当前争议主链路。
        </p>
      </div>
      <strong>默认最终介入 · 80 分门禁 · 最多重生成 2 次</strong>
    </header>

    <div class="agent-console__layout">
      <aside class="agent-rail" aria-label="数字人角色轨道">
        <div class="agent-rail__heading">
          <span>AGENT RAIL</span>
          <h2>数字人角色轨道</h2>
        </div>

        <button
          v-for="agent in agents"
          :key="agent.name"
          class="agent-ticket"
          :class="{ 'agent-ticket--active': agent.active }"
          type="button"
          data-agent-role
        >
          <span class="agent-ticket__avatar" aria-hidden="true">{{ agent.icon }}</span>
          <span>
            <strong>{{ agent.name }}</strong>
            <small>{{ agent.summary }}</small>
          </span>
          <i>{{ agent.status }}</i>
        </button>
      </aside>

      <main class="agent-workbench">
        <section class="agent-panel">
          <header class="agent-panel__header">
            <div>
              <span>AI JURY CONFIG</span>
              <h2>AI 评审团 · 圆桌团</h2>
              <p>
                管理它什么时候出场、看哪些材料、打几分才算通过，以及失败时如何引导
                AI 法官重生成。当前页面只做前端预留，后期接入真实配置后端。
              </p>
            </div>
            <strong>版本：jury-profile-v1</strong>
          </header>

          <div class="agent-config-tabs">
            <article v-for="[title, summary] in configTabs" :key="title">
              <strong>{{ title }}</strong>
              <small>{{ summary }}</small>
            </article>
          </div>

          <div class="jury-strategies" aria-label="评审团介入策略">
            <article
              v-for="strategy in strategies"
              :key="strategy.code"
              class="jury-strategy"
              :class="{
                'jury-strategy--selected': strategy.code === 'FINAL_ONLY',
                'jury-strategy--recommended': strategy.tag,
              }"
              :data-jury-strategy="strategy.code"
            >
              <div class="jury-strategy__flow">
                <span
                  v-for="[node, color] in strategy.nodes"
                  :key="node"
                  :class="`jury-strategy__node jury-strategy__node--${color}`"
                >
                  {{ node }}
                </span>
              </div>
              <span v-if="strategy.tag" class="jury-strategy__tag">{{ strategy.tag }}</span>
              <h3>{{ strategy.title }}</h3>
              <p>{{ strategy.description }}</p>
            </article>
          </div>
        </section>

        <section class="agent-console__preview">
          <article class="prompt-card">
            <span>PROFILE / PROMPT / SKILL</span>
            <h2>配置面板预览</h2>
            <dl>
              <div>
                <dt>数字人名称</dt>
                <dd>圆桌团 · AI 评审团</dd>
              </div>
              <div>
                <dt>系统 Prompt 版本</dt>
                <dd>jury-final-gate-v1</dd>
              </div>
            </dl>
            <p>
              你是 AI 评审团，只能对 AI 法官的裁决草案进行事实一致性、规则适用、风险遗漏和公平性复核。
              你不得直接作出最终裁决，也不得触发退款、补发或关闭售后。
            </p>
            <div class="skill-badges">
              <span>evidence.read</span>
              <span>draft.read</span>
              <span>ruleset.read</span>
              <span>revision.suggest</span>
            </div>
          </article>

          <article class="court-mapping">
            <span>HEARING MAPPING</span>
            <h2>映射到小法庭</h2>
            <div class="court-preview">
              <b class="court-preview__judge">⚖️ AI 法官</b>
              <b class="court-preview__user">🧑 用户席</b>
              <b class="court-preview__clerk">📚 书记官</b>
              <b class="court-preview__merchant">🏪 商家席</b>
              <b class="court-preview__jury">💬 评审团</b>
            </div>
            <p>
              小法庭仍保持现有卡通法庭氛围；评审团不常驻打断庭审，而是在最终草案节点以“圆桌复核卡”
              出现，展示评分、问题、修改意见和是否通过。
            </p>
          </article>
        </section>
      </main>
    </div>
  </section>
</template>

<style scoped>
.agent-console {
  display: grid;
  width: 100%;
  min-width: 0;
  gap: 18px;
  box-sizing: border-box;
}

.agent-console,
.agent-console * {
  box-sizing: border-box;
}

.agent-console :where(header, aside, main, section, article, div, button, span, strong, small, i, p, h1, h2, h3, dl, dt, dd, b) {
  min-width: 0;
}

.agent-console :where(span, strong, small, i, p, h1, h2, h3, dt, dd, b) {
  overflow-wrap: anywhere;
  word-break: break-word;
}

.agent-console__intro {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: 24px;
  align-items: end;
}

.agent-console__intro span,
.agent-rail__heading span,
.agent-panel__header span,
.prompt-card > span,
.court-mapping > span {
  color: #7486a3;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .16em;
}

.agent-console__intro h1 {
  margin: 5px 0 8px;
  color: #33435c;
  font-size: clamp(28px, 4vw, 42px);
  letter-spacing: -.04em;
}

.agent-console__intro p,
.agent-panel__header p,
.jury-strategy p,
.prompt-card p,
.court-mapping p {
  margin: 0;
  color: #6f7d92;
  line-height: 1.7;
}

.agent-console__intro > strong,
.agent-panel__header > strong {
  flex: 0 0 auto;
  max-width: 100%;
  padding: 10px 13px;
  overflow-wrap: anywhere;
  color: #8b5272;
  background: #fff0f4;
  border: 1px solid #f2d7df;
  border-radius: 14px;
  font-size: 12px;
}

.agent-console__layout {
  display: grid;
  min-width: 0;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 18px;
}

.agent-rail,
.agent-panel,
.prompt-card,
.court-mapping {
  min-width: 0;
  background: #ffffffd9;
  border: 1px solid #dfe8f2;
  border-radius: 28px;
  box-shadow: 0 22px 56px #536c8b10;
}

.agent-rail {
  padding: 18px;
}

.agent-rail h2,
.agent-panel h2,
.prompt-card h2,
.court-mapping h2 {
  margin: 5px 0 14px;
  color: #33435c;
}

.agent-ticket {
  display: grid;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  width: 100%;
  padding: 12px;
  margin-bottom: 10px;
  text-align: left;
  color: inherit;
  background: #f8fbff;
  border: 1px solid #e1e9f2;
  border-radius: 18px;
  cursor: default;
}

.agent-ticket--active {
  background: linear-gradient(135deg, #eaf7ff, #f4eeff);
  border-color: #cedcf1;
  box-shadow: inset 0 0 0 1px #fff;
}

.agent-ticket__avatar {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  background: linear-gradient(135deg, #e4f7ff, #fff1cb);
  border: 1px solid #d7e7ef;
  border-radius: 16px;
  font-size: 22px;
}

.agent-ticket strong {
  display: block;
  color: #3d4d65;
  font-size: 14px;
}

.agent-ticket small {
  display: block;
  margin-top: 3px;
  color: #7a899f;
  font-size: 10px;
  line-height: 1.45;
}

.agent-ticket i {
  max-width: 100%;
  padding: 5px 7px;
  overflow-wrap: anywhere;
  color: #657a9b;
  background: #fff;
  border: 1px solid #e1e9f2;
  border-radius: 10px;
  font-size: 10px;
  font-style: normal;
  font-weight: 900;
  white-space: normal;
}

.agent-workbench {
  display: grid;
  min-width: 0;
  gap: 18px;
}

.agent-panel {
  padding: 20px;
}

.agent-panel__header {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: 18px;
  align-items: start;
  margin-bottom: 16px;
}

.agent-config-tabs {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.agent-config-tabs article {
  min-width: 0;
  padding: 13px;
  background: #f7f9fc;
  border: 1px solid #e2e9f1;
  border-radius: 18px;
}

.agent-config-tabs strong {
  display: block;
  color: #3d4d65;
}

.agent-config-tabs small {
  display: block;
  margin-top: 5px;
  color: #8290a5;
  line-height: 1.45;
}

.jury-strategies {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.jury-strategy {
  position: relative;
  min-height: 230px;
  min-width: 0;
  padding: 16px;
  overflow: hidden;
  background: #ffffffd9;
  border: 1px solid #dfe8f2;
  border-radius: 24px;
}

.jury-strategy--selected {
  border-color: #b9d4f3;
  box-shadow: 0 24px 64px #506c941d, inset 0 0 0 2px #fff;
}

.jury-strategy__tag {
  position: absolute;
  top: 14px;
  right: 14px;
  padding: 5px 8px;
  color: #fff;
  background: #ff826d;
  border-radius: 10px;
  font-size: 10px;
  font-weight: 900;
}

.jury-strategy h3 {
  margin: 12px 0 8px;
  color: #3d4d65;
}

.jury-strategy__flow {
  display: grid;
  gap: 7px;
}

.jury-strategy__node {
  padding: 8px 10px;
  overflow-wrap: anywhere;
  border-radius: 14px;
  font-size: 12px;
  font-weight: 800;
  text-align: center;
}

.jury-strategy__node--blue {
  background: #e5f6ff;
  border: 1px solid #c9e7f6;
}

.jury-strategy__node--yellow {
  background: #fff5d8;
  border: 1px solid #f0dfaa;
}

.jury-strategy__node--purple {
  background: #f0ebff;
  border: 1px solid #ddd2ff;
}

.jury-strategy__node--green {
  background: #e8f8ef;
  border: 1px solid #c8ead5;
}

.jury-strategy__node--pink {
  background: #fff0f4;
  border: 1px solid #f2d7df;
}

.agent-console__preview {
  display: grid;
  min-width: 0;
  grid-template-columns: 1.1fr .9fr;
  gap: 16px;
}

.prompt-card,
.court-mapping {
  padding: 18px;
}

.prompt-card dl {
  display: grid;
  gap: 10px;
  margin: 0 0 12px;
}

.prompt-card dl div {
  display: grid;
  gap: 6px;
  padding: 13px;
  background: #f8fbff;
  border: 1px solid #e1e9f2;
  border-radius: 16px;
}

.prompt-card dt {
  color: #71819a;
  font-size: 10px;
  font-weight: 900;
  letter-spacing: .09em;
}

.prompt-card dd {
  margin: 0;
  color: #53647c;
  font-weight: 800;
}

.prompt-card p {
  padding: 12px;
  background: #fff;
  border: 1px dashed #cbd8e8;
  border-radius: 15px;
  font-size: 12px;
}

.skill-badges {
  display: flex;
  min-width: 0;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}

.skill-badges span {
  max-width: 100%;
  padding: 7px 9px;
  overflow-wrap: anywhere;
  color: #53619a;
  background: #edf6ff;
  border: 1px solid #dce6f3;
  border-radius: 11px;
  font-size: 11px;
  font-weight: 800;
}

.court-preview {
  display: grid;
  min-width: 0;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-template-areas:
    ". judge ."
    "user clerk merchant"
    ". jury .";
  gap: 10px;
  padding: 18px;
  margin-bottom: 12px;
  background:
    radial-gradient(circle at 50% 0, #fff4cc 0 11%, transparent 12%),
    linear-gradient(180deg, #edf8ff, #f7f1ff);
  border: 2px solid #fff;
  border-radius: 32px 32px 18px 18px;
}

.court-preview b {
  min-width: 0;
  padding: 11px;
  overflow-wrap: anywhere;
  text-align: center;
  border-radius: 16px;
  font-size: 12px;
}

.court-preview__judge {
  grid-area: judge;
  background: #fff5d8;
  border: 1px solid #f0dfaa;
}

.court-preview__user {
  grid-area: user;
  background: #e5f6ff;
}

.court-preview__clerk {
  grid-area: clerk;
  background: #fff;
  border: 1px dashed #d9dfea;
}

.court-preview__merchant {
  grid-area: merchant;
  background: #e8f8ef;
}

.court-preview__jury {
  grid-area: jury;
  background: #f0ebff;
}

@media (max-width: 1120px) {
  .agent-console__layout,
  .agent-console__preview {
    grid-template-columns: 1fr;
  }

  .agent-config-tabs,
  .jury-strategies {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .agent-console__intro,
  .agent-panel__header {
    display: grid;
  }
}
</style>
