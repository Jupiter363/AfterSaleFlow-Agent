# Fixed-Shell Room-by-Room Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按页面/房间严格串行修复固定外框内的长文本、滚动责任和响应式错乱，每完成一个房间就停止并交给用户在右侧浏览器验收。

**Architecture:** 每轮只允许当前房间、当前房间测试及其首次实际使用的共享能力进入改动范围。房间分支通过单元测试、真实浏览器布局测试、构建和人工浏览器验收后才能合并 `main`；用户未确认前禁止进入下一房间。

**Tech Stack:** Vue 3.5、Vite 6、Vitest 3、jsdom、Playwright Chromium、pnpm 11.7、GitHub Actions。

---

## 串行交付门

每个房间必须依次完成：

- [ ] 从最新 `main` 创建当前任务明确指定的独立 `codex/layout-*` 分支或 worktree。
- [ ] 先写当前房间的失败测试，再写实现。
- [ ] 只修改当前房间和当前消费者需要的共享代码。
- [ ] 运行当前房间单测、完整前端测试、构建和 Chromium 布局测试。
- [ ] 用真实浏览器复核设计规格中的断点和长文本数据。
- [ ] 检查 diff，确认没有无意修改下一房间。
- [ ] 提交并推送当前房间分支。
- [ ] 在右侧浏览器打开当前房间的真实验收 URL。
- [ ] 停止开发，报告本轮尺寸、滚动区、测试、提交号和 3–5 个观察点。
- [ ] 等待用户明确回复“确认”或提出修改意见。
- [ ] 用户确认后合并并推送 `main`，才开始下一房间。

如果用户要求调整，继续停留在当前房间分支，修正并重新验收；不得提前修改其他页面。

## 任务顺序

### Task 1：争议接待室

**Route:** `/disputes/:caseId/intake`

**Branch:** `codex/layout-intake-room`
**Detailed plan:** `docs/superpowers/plans/2026-07-11-intake-room-fixed-shell-layout.md`

**范围：**

- 左右主卡固定 740px。
- 左侧消息列表成为唯一滚动区，输入台固定。
- 右侧重新分配摘要、诉求/回应、案件索引、原始陈述和核验重点。
- 修复 581→580px 原始陈述高度归零。
- 首次引入 `ExpandableText` 和真实浏览器布局测试基础。
- 为消除当前房间 320px 横向滚动，可移除根节点 `min-width: 320px`；必须同步做全页面宽度 smoke，不得顺手改其他页面布局。

**验收视口：** `1121/1120`、`1061/1060`、`981/980`、`621/620`、`581/580`、390、320、1024×600。

**硬停点：** 用户在真实接待室确认左右固定高度、原始陈述、核验重点、按钮和聊天滚动后才进入 Task 2。

### Task 2：证据书记官室

**Route:** `/disputes/:caseId/evidence`
**Branch:** `codex/layout-evidence-room`

**范围：**

- 复用已经验收的 740px 房间外框合同。
- 证据列表成为右卡唯一滚动区。
- 320px 下重排标题、倒计时、AI 提示、上传台和底部操作。
- 文件名、核验状态、可信分和证据说明按内容类型重排。
- 压测 100 张证据、200 字无空格文件名和长 AI 提示。

**验收角色：** USER、MERCHANT。
**硬停点：** 在证据室保持列表滚动位置和操作按钮可见，用户确认后才进入 Task 3。

### Task 3：共享小法庭

**Route:** `/disputes/:caseId/hearing`
**Branch:** `codex/layout-hearing-court`

**范围：**

- 固定 720–820px 庭审画布。
- 状态坞固定 122px，输入坞固定 154px，消息区占剩余空间。
- 容器 `>=1220px` 三栏，`<1220px` 左右证据改为抽屉。
- 三阶段始终横向排列。
- 1499/1500/2000 字消息边界、50 条消息、100 张证据压力测试。
- USER、MERCHANT、PLATFORM_REVIEWER 三角色验证。

**验收视口：** `1260/1259`、`1221/1220`、`1181/1180`、`681/680`、390、320、1024×600。
**硬停点：** 右侧浏览器保留一侧证据抽屉打开供用户检查；确认后才进入 Task 4。

### Task 4：争议订单总览

**Route:** `/disputes`
**Branch:** `codex/layout-dispute-overview`

**范围：**

- 五阶段始终为横向轨道，窄屏只滚动轨道。
- 案件索引维持 2×2。
- 解决阶段 4/5 与案件卡覆盖。
- 实装 720/810/880/940px 分级固定工作区合同。

**验收视口：** `1021/1020`、`681/680`、`361/360`、390、320、1024×600。
**硬停点：** 打开 `/disputes`，用户确认阶段轨道和案件卡后才进入 Task 5。

### Task 5：裁决草案与最终结果

**Route:** `/disputes/:caseId/outcome`
**Branch:** `codex/layout-outcome`

**范围：**

- 同时覆盖草案态和最终态，不修改业务逻辑。
- 页面保持文档流，模块使用稳定 `min-height`。
- 520px 以下审核操作台改为单列。
- 消除 390/320px 页面级横向滚动。
- USER 与 PLATFORM_REVIEWER 视角分别验证。

**验收视口：** `521/520`、`461/460`、390、320。
**硬停点：** 优先打开审核员草案页，同时提供用户视角 URL；用户确认后才进入 Task 6。

### Task 6：审核队列

**Route:** `/reviews`
**Branch:** `codex/layout-review-queue`

**范围：** 长标题、长案件号以及 0/1/50/100 条任务；只处理列表、筛选区和页面滚动。

**硬停点：** 打开 `/reviews`，用户确认后才进入 Task 7。

### Task 7：审核工作台

**Route:** `/reviews/:reviewId`
**Branch:** `codex/layout-review-workbench`

**范围：**

- 六张卡使用 12 列业务布局。
- 修复同排等高和冻结索引数千像素空白。
- 卡片正文超容量时才滚动。
- 约 14k 字符 ReviewPacket 压力测试。
- 倒计时和服务状态分成两行。

**硬停点：** 打开最长审核包供用户检查；确认后才进入 Task 8。

### Task 8：数字人管理

**Route:** `/agents`
**Branch:** `codex/layout-agent-console`

**范围：** 推荐标签进入正常 Grid，使用 20 字推荐标签和三个 40 字阶段名验证 320px 下不覆盖。

**硬停点：** 打开 `/agents`，用户确认后才进入 Task 9。

### Task 9：传票信箱与剩余全局壳层

**Routes:** 全局组件
**Branch:** `codex/layout-mailbox-shell`

**范围：**

- Mailbox 使用 `Teleport to="body"`。
- 固定 Header，只有通知列表滚动。
- body 锁定、44×44 关闭按钮、点击通知先关闭后导航。
- 100 条通知、长 URL、320/390px 验证。
- 复核 Task 1 中最小宽度调整对所有已验收页面的最终影响。

**硬停点：** 总览页保持信箱展开，用户确认后进入最终回归。

### Task 10：全页面最终回归

**Branch:** `codex/layout-final-regression`

只允许修复前九轮验收遗漏，不再进行新的视觉设计。运行 8 个生产页面、三角色、全部规定断点和压力数据，形成最终验收报告并停止等待用户确认。

## 共享能力归属

- `ExpandableText`：Task 1 首次创建，只在接待室启用。
- Playwright 浏览器布局基础：Task 1 首次创建，后续房间追加独立 spec。
- 庭审证据抽屉：Task 3 创建，不能复用 Mailbox 的全局行为。
- body 锁定与全局抽屉能力：Task 9 创建。
- 后续修改已经验收的共享组件时，必须同步重跑其首个消费房间。

禁止并行编辑不同房间。子代理只能并行执行当前房间的测试审计、实现复核和浏览器验收。
