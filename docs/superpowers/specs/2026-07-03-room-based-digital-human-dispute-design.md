# 房间式数字人履约争端审理系统设计规格

**日期：** 2026-07-03  
**状态：** 已完成产品讨论，待用户审阅书面规格  
**适用基线：** `codex/ai-native-final-refactor`

## 1. 设计目标

系统从“若干 AI 功能页面”重构为“数字人接力办理的一组争端业务房间”。用户、商家与平台人员围绕同一争议案件协作，依次经过争议接待室、证据书记官室、小法庭和平台终审。

系统仍然只处理履约争端，不建设普通订单中心、物流监控大盘或普通售后流。总览页只展示：

1. 通过外部接口导入的争议订单；
2. 通过争议接待官创建的争议订单。

首版使用数据库种子模拟外部接口已导入的争议订单，同时保留幂等的外部导入适配接口。

## 2. 核心设计原则

1. **Workflow 控制流程与时效。** 两小时举证、三小时庭审和三轮上限均由 Temporal 持久化控制，不能依赖浏览器计时。
2. **Agent 承担认知工作。** 接待、证据整理、主持审理、按需评议和审核解释由不同 Agent 完成。
3. **数字人是 Agent 的可视化身份。** 数字人负责表达状态和引导交互，不获得超出 Agent Profile 的权限。
4. **人类承担最终责任。** AI 主审官只能生成裁决草案；平台审核员批准后才能进入确定性执行。
5. **对话也是业务事实。** 房间消息、确认动作、举证完成、和解确认和审理发言均不可变保存并进入审计链。
6. **共享协作不等于完全公开。** 双方共享证据目录，原件按角色、隐私级别和脱敏规则授权。

## 3. 角色与权限

| 角色 | 主要能力 | 明确禁止 |
|---|---|---|
| USER | 查看自身相关争议、参与接待、举证、庭审、和解确认、查看结果 | 查看商家私密材料、审核内部信息、执行裁决 |
| MERCHANT | 查看店铺相关争议、参与接待、举证、庭审、和解确认、查看结果 | 查看用户私密材料、审核内部信息、执行裁决 |
| CUSTOMER_SERVICE | 查看授权案件、协助异常案件、发起人工接管 | 批准裁决、执行高风险动作 |
| PLATFORM_REVIEWER | 庭审期间只读旁观；ReviewPacket 冻结后进行终审 | 修改原始证据、以审核辅助官代替本人决策 |
| ADMIN | 配置、审计、指标和故障处理 | 默认不具有案件审批权 |

案件受理后，发起方和相对方都写入 `case_participant`。所有查询先校验案件成员关系，再按角色生成脱敏投影。

## 4. 产品信息架构

### 4.1 争议办理总览

路由：`/disputes`

桌面端采用混合布局：

- 左侧是当前角色涉及的全部争议订单栏，支持按状态、待办方、剩余时效和风险筛选；
- 右侧是当前选中订单的“审理游园”状态路线图；
- 点击左侧订单只切换右侧旅程；
- 点击路线节点或“进入当前房间”按钮才进入对应业务房间；
- 右上角显示卡通传票信箱和未读角标。

争议卡片至少展示案件编号、订单引用、来源、争议类型、当前房间、案件状态、待处理方、当前截止时间、剩余时长、风险和未读消息数。

移动端使用“争议订单列表 → 案件旅程 → 对应房间”的三级导航。

### 4.2 争议接待室

路由：`/disputes/:caseId/intake`

争议接待官通过数字人对话完成：

- 判断请求是否构成履约争端；
- 抽取发起方、订单、售后和物流引用；
- 抽取用户与商家主张；
- 识别诉求与期望处理结果；
- 识别初始风险信号；
- 输出受理建议、缺失信息和置信度。

Agent 输出以可确认的结构化“卷宗贴纸”呈现。发起方可以更正字段，但不能直接篡改 Agent 原始输出。

受理后：

1. 创建或确认案件参与方；
2. 向相对方发送站内争议传票；
3. 开放证据书记官室；
4. 启动两小时举证时钟。

不予受理时保留审计记录，状态为 `NOT_ADMISSIBLE`，不邀请相对方、不开放后续房间、不进入普通售后流。该记录默认折叠到总览页“已结束/未受理”筛选。

### 4.3 证据书记官室

路由：`/disputes/:caseId/evidence`

页面顶部固定显示服务端两小时倒计时。用户和商家分别与证据书记官对话，支持文本、图片、视频和文件。

证据视图由三部分组成：

- 用户提交桌；
- 商家提交桌；
- 双方共享的证据目录与证据书架。

双方均能看到证据类型、提交方、提交时间、解析状态、可信度状态和是否进入卷宗。原件按以下可见级别处理：

- `PARTIES`：脱敏后双方及平台可见；
- `PRIVATE`：提交方和授权平台人员可见；
- `PLATFORM`：仅授权平台人员可见。

证据书记官采用分级可信度模型：

- `VERIFIED`：确定性来源、签名、哈希或平台数据校验通过；
- `PLAUSIBLE`：内容与上下文基本一致，但缺少确定性来源证明；
- `SUSPICIOUS`：存在元数据异常、疑似篡改或重大冲突；
- `REJECTED`：格式、合法性或可采性规则明确不通过；
- `NEEDS_HUMAN_REVIEW`：必须人工核验。

被拒绝或隔离的材料不物理删除，保留哈希、原因和审计信息，但不进入可供主审官使用的冻结卷宗。

双方均点击“本轮举证完成”时可以提前封卷并开庭；否则两小时到期自动封存当前有效卷宗并开放小法庭。迟到证据只能通过庭审补证入口提交，并创建新卷宗版本。

### 4.4 小法庭

路由：`/disputes/:caseId/hearing`

小法庭是用户、商家和平台审核员共享的案件空间：

- 用户与商家可以陈述、质证、补证和确认和解方案；
- 平台审核员在裁决草案冻结前只读旁观；
- 客服仅在获得案件授权时介入异常处理。

庭审流程：

1. 书记官陈列双方有效证据和证据缺口；
2. AI 主审官形成首轮争点和初步回复；
3. 双方陈述或提交补充证据；
4. 主审官进行交叉核验和规则适用；
5. 达到停止条件后生成非最终裁决草案。

庭审最多三轮：初审一轮，补证或质证最多两轮。小法庭开放时启动三小时时钟，补证不重置时钟。

以下任一条件触发强制收敛：

- 三小时到期；
- 三轮耗尽；
- 双方完成同一版本和解方案的双确认；
- 主审官认为事实已经充分且双方无新增请求。

超时时，Workflow 冻结最新有效卷宗、陈述和确认记录，要求主审官基于现状生成裁决草案，并明确记录证据缺口、超时事实、不确定性和是否需要人工重点关注。

### 4.5 和解方案

庭审聊天中的自然语言不能直接视为达成一致。系统必须生成版本化 `settlement_proposal`，由用户和商家分别确认同一版本。

任一方修改方案都会创建新版本并使旧确认失效。双方确认同一版本后，AI 主审官以和解方案为基础生成裁决草案，仍须平台审核员终审。

### 4.6 按需 AI 评审团

AI 评审团不对每个案件固定运行。出现以下任一条件时触发：

- 高风险或关键动作金额超过策略阈值；
- 双方未达成一致；
- 主审官低置信度；
- 存在重大证据冲突；
- 规则适用不确定；
- Guardrail 或 Approval Policy 要求评议。

低风险、双方一致、证据充分且规则明确的案件可以跳过评议团，直接生成 ReviewPacket，降低时延和 Token 成本。

### 4.7 平台终审与结果

审核任务列表路由：`/reviews`  
审核工作台路由：`/reviews/:reviewId`

ReviewPacket 冻结后，审核员角色才看到审核辅助官、方案解释和审核动作。用户与商家只看到“平台终审中”，不能访问审核辅助官的问答与内部风险信息。

审核员可以批准、修改后批准、退回补证、拒绝或升级。所有动作必须校验审核角色、packet 版本、action hash、有效期和二次确认。

最终结果页展示人类最终决定、实际执行动作、执行状态、关键依据和可追溯时间线。

## 5. 数字人体验

首版采用 2D 卡通数字人，不使用真人视频或 3D 渲染。每个数字人至少支持：

- `IDLE`：待机；
- `LISTENING`：倾听用户输入；
- `THINKING`：Agent 运行中；
- `SPEAKING`：结构化输出正在呈现；
- `COMPLETED`：本阶段完成；
- `HANDOFF`：转交下一数字人或人工；
- `ERROR`：失败并提供可恢复动作。

首版使用 SVG 与 CSS 动效，组件接口保留替换为 Rive 或 Lottie 资产的能力。所有动画尊重 `prefers-reduced-motion`。

数字人角色包括争议接待官、证据书记官、AI 主审官、按需出现的 AI 评审团和仅审核员可见的审核辅助官。

## 6. 传票信箱

系统不接入短信供应商。传票信箱是平台内的可靠通知中心。

通知类型至少包括：

- 受理结果；
- 争议传票；
- 证据室开放；
- 举证临期；
- 举证结束和开庭；
- 待补证；
- 和解方案待确认；
- 裁决草案已提交终审；
- 平台终审结果；
- 执行完成或人工接管。

通知按角色生成脱敏内容。点击消息直接进入对应案件和房间，并记录已读时间。通知通过事务 Outbox 从业务事件生成，使用业务事件键保证幂等。

## 7. 状态与时钟

案件目标状态：

```text
INTAKE_PENDING
INTAKE_IN_PROGRESS
NOT_ADMISSIBLE
EVIDENCE_OPEN
EVIDENCE_SEALED
HEARING_OPEN
SETTLEMENT_PENDING
DRAFT_READY
DELIBERATION_RUNNING
REVIEW_PENDING
MANUAL_HANDOFF
APPROVED_FOR_EXECUTION
EXECUTING
CLOSED
CANCELLED
```

房间状态：

```text
LOCKED
OPEN
WAITING
SEALED
CLOSED
```

时钟状态：

```text
SCHEDULED
RUNNING
COMPLETED_EARLY
EXPIRED
CANCELLED
```

生产默认配置：

```text
EVIDENCE_WINDOW=PT2H
HEARING_WINDOW=PT3H
MAX_HEARING_ROUNDS=3
```

测试环境允许覆盖为秒级时长，但业务代码不得写死测试时钟。

## 8. 数据模型

保留现有案件、证据、卷宗、审理、评议、审核、执行、Agent 与审计表，新增或扩展：

| 对象 | 职责 |
|---|---|
| `fulfillment_dispute_case` | 增加 `source_type`、`external_case_ref`、`current_room`、`current_deadline_at` |
| `case_participant` | 案件成员、角色、加入状态、可见范围 |
| `case_room` | 房间类型、状态、开放/关闭时间和当前时钟 |
| `room_message` | 不可变对话、发言主体、受众、附件引用、轮次和 Agent Run 引用 |
| `case_phase_clock` | 举证与庭审时钟的服务端投影 |
| `evidence_verification` | 证据校验结果、依据、风险和人工复核要求 |
| `evidence_party_completion` | 双方举证完成确认 |
| `hearing_round` | 轮次状态、冻结卷宗版本和停止原因 |
| `settlement_proposal` | 版本化和解内容、提出方和状态 |
| `settlement_confirmation` | 用户与商家对指定版本的确认 |
| `notification` | 角色收件箱、未读状态和深链 |
| `notification_outbox` | 可靠、幂等的站内通知投递 |
| `case_timeline_event` | 增加单调序号、房间、受众和 SSE 事件元数据 |

外部导入使用 `(source_system, external_case_ref)` 唯一约束。房间消息、确认、时钟到期和通知均使用业务幂等键。

## 9. API 设计

所有正式外部 API 使用无版本号路径，写请求支持 `Idempotency-Key`。

### 9.1 争议与导入

```text
GET  /api/disputes
POST /api/disputes
GET  /api/disputes/{caseId}
POST /api/disputes/{caseId}/intake/start
POST /internal/disputes/import
```

`GET /api/disputes` 返回总览卡片所需的当前房间、截止时间、待办、未读数和来源，不返回普通订单。

### 9.2 房间与对话

```text
GET  /api/disputes/{caseId}/rooms/{roomType}
GET  /api/disputes/{caseId}/rooms/{roomType}/messages
POST /api/disputes/{caseId}/rooms/{roomType}/messages
POST /api/disputes/{caseId}/intake/analyze
POST /api/disputes/{caseId}/intake/confirm
```

消息接口只接受白名单消息类型和附件引用。Agent 回复通过 Agent Run 生成并写入同一消息流。

### 9.3 证据

```text
GET  /api/disputes/{caseId}/evidence
POST /api/disputes/{caseId}/evidence
GET  /api/disputes/{caseId}/evidence-dossiers/{version}
POST /api/disputes/{caseId}/evidence/complete
GET  /api/disputes/{caseId}/evidence/completion
```

证据列表根据角色返回共享目录字段和授权内容。举证完成动作必须幂等。

### 9.4 庭审与和解

```text
GET  /api/disputes/{caseId}/hearing
POST /api/disputes/{caseId}/hearing/statements
POST /api/disputes/{caseId}/hearing/supplements
POST /api/disputes/{caseId}/hearing/settlements
POST /api/disputes/{caseId}/hearing/settlements/{version}/confirm
GET  /api/disputes/{caseId}/adjudication-drafts/latest
GET  /api/disputes/{caseId}/deliberation
```

### 9.5 实时事件与信箱

```text
GET  /api/disputes/{caseId}/events
GET  /api/notifications
GET  /api/notifications/unread-count
POST /api/notifications/{notificationId}/read
POST /api/notifications/read-all
```

案件事件使用 SSE。客户端通过 `Last-Event-ID` 续传；服务端按案件成员关系和事件受众过滤。

### 9.6 审核

审核接口统一为：

```text
GET  /api/reviews
GET  /api/reviews/{reviewId}/packet
POST /api/reviews/{reviewId}/decision
POST /api/reviews/{reviewId}/copilot/query
```

`decision` 使用受控枚举表达批准、修改后批准、退回、拒绝和升级，避免创建多套重复动作端点。

## 10. Workflow 设计

主 Workflow 顺序：

```text
案件导入或创建
→ 接待分析与发起方确认
→ 受理并邀请双方 / 不予受理终止
→ 开放证据室并启动 PT2H
→ 双方提前完成或时钟到期
→ 冻结卷宗版本
→ 开放小法庭并启动 PT3H
→ 最多三轮陈述、质证和补证
→ 双确认和解 / 事实充分 / 三轮耗尽 / 三小时到期
→ 生成非最终裁决草案
→ 根据风险条件决定是否运行 AI 评审团
→ 冻结 ReviewPacket
→ 平台审核员终审
→ 确定性执行
→ 关闭与离线评估
```

Temporal 是时钟与流程状态源；PostgreSQL 保存面向 API 查询的状态投影。重复 Signal、超时回调或页面重试不能重复封卷、重复生成草案或重复投递通知。

## 11. Figma 与前端设计

Figma 不使用单一后台模板，而是组合多种轻法庭空间：

1. **争议总览：** 左侧争议订单栏，右侧审理游园，右上角传票信箱。
2. **接待室：** 数字人会客空间，对话与卷宗贴纸共同构成主界面。
3. **证据室：** 证据书房、双方提交桌、共享证据书架和两小时计时器。
4. **小法庭：** 书记官席、主审官席、双方发言席、三轮轨迹、三小时时钟、和解桌和按需评议席。
5. **平台终审：** “会呼吸的卷宗”认知地图和仅审核员可见的审核辅助官。
6. **结果页：** 盖章裁决卷轴、执行进度和可追溯依据。
7. **传票信箱：** 全局抽屉与独立通知状态。

视觉使用暖白、天蓝、珊瑚橙、嫩芽绿和柔和紫；避免黑金、庄严法徽和传统电商后台。法庭元素以小法槌、卷宗、席位、印章和传票表达，保持活泼但不削弱人类终审提示。

每个 Figma 页面必须定义桌面关键帧、角色变体、空状态、运行状态、超时状态、错误状态和移动端核心状态。前端只有在具体 Figma 节点通过评审后才进行 Vue 映射。

## 12. 文档更新范围

先更新 `Project Plan`，再修改代码。文档必须统一以下内容：

- 产品故事与房间式信息架构；
- 角色权限与数字人边界；
- 两小时举证、三小时庭审和三轮上限；
- 证据可信度与共享目录模型；
- 双确认和解与按需评议团；
- 传票信箱和 SSE；
- 新增数据表、API、配置、错误码和事件；
- 逐页 Figma 提示词；
- 新的测试与最终验收项。

现有文档中“传统三栏工作台”“独立评议团页面”“旧 `/api/v1` 路径”“普通履约流”和与本规格冲突的页面结构必须删除或改写。所有文件继续作为最终版治理，不创建 `v2` 文档。

## 13. 错误处理与恢复

- Agent 失败：保留用户消息，提供重试或人工接管，不丢失房间状态。
- SSE 断线：使用事件序号续传；客户端先拉取房间快照再补事件。
- 证据解析失败：原件仍保留，状态为解析失败，可重试或人工核验。
- 时钟到期：由 Workflow 触发一次性状态转换，前端本地倒计时不能触发业务动作。
- 一方未举证：到期后以现有材料封卷，并明确记录缺席和举证缺口。
- 庭审超时：强制草案并提高人工关注级别，不能无限等待。
- 和解版本竞争：只允许确认当前有效版本，旧版本确认返回版本冲突。
- 重复通知：Outbox 业务键去重，读状态与投递状态分离。
- 越权访问：返回统一权限错误并记录审计，不暴露案件是否存在。

## 14. 测试与验收

必须覆盖：

1. 外部争议幂等导入与数据库种子展示；
2. 接待官受理、缺信息、不予受理和人工接管；
3. 受理后双方成员建立及争议传票生成；
4. 双方提前完成举证并提前开庭；
5. 两小时到期、单方缺席和迟到证据进入补证轮次；
6. 五级证据可信度、隔离、脱敏和角色可见性；
7. 三轮庭审正常完成、三轮耗尽和三小时强制草案；
8. 和解方案新版本使旧确认失效，双确认后生成和解型草案；
9. AI 评审团触发与跳过条件；
10. 审核辅助官仅审核员可见；
11. ReviewPacket 版本、hash、角色和有效期校验；
12. SSE 顺序、角色过滤、断线续传和重复事件去重；
13. 传票信箱未读数、深链、已读和 Outbox 幂等；
14. 用户、商家、客服、审核员和管理员越权测试；
15. Figma 视觉验收、桌面/移动响应式和减少动画模式；
16. 完整 E2E：导入或创建 → 接待 → 举证 → 庭审 → 评议或跳过 → 终审 → 执行 → 关闭。

生产验收使用 `PT2H / PT3H / 3轮` 配置检查，自动化测试使用可注入时钟和秒级覆盖。

## 15. 实施顺序

```text
正式规格审阅
→ Project Plan 文档统一
→ 数据库迁移与演示种子
→ 房间、参与方、时钟、事件与信箱后端
→ Workflow 与 Agent 协议补充
→ API 合约与集成测试
→ 按文档生成逐页 Figma 设计并评审
→ Vue 页面与角色视图映射
→ 前后端联调和 E2E
→ 按最终验收清单逐项验收
```

当前未提交的前端路由、Store、API 和基础组件可以评估复用，但不得在 Figma 定稿前继续扩展页面。旧普通履约流和与最终房间架构冲突的页面在迁移完成后删除。
