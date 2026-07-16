# AI Native 履约争端审理系统：前端交互设计技术方案（正式补充版）

> 文档性质：正式版开发文档的前端交互专项补充  
> 适用范围：前端产品设计、前端架构设计、交互流程设计、组件设计、状态设计、接口联调、测试验收  
> 当前任务边界：先完成前端交互与逐页 Figma 方案，再按通过评审的节点映射 Vue 页面并完成联调
> 产品主线：只处理用户或商家主动发起的履约争端，不建设订单中心、物流监控或泛售后工作台  
> 核心目标：通过 AI Native 交互方式，把“履约争端审理”从传统表单后台升级为“人机协同审理空间”

---

## 0. 文档定位

正式版前端只保留以下用户可见页面：

```text
/disputes                     争议办理总览
/disputes/:caseId/intake      争议接待室
/disputes/:caseId/evidence    证据书记官室
/disputes/:caseId/hearing     小法庭
/disputes/:caseId/outcome     最终结果
/reviews                      平台审核任务
/reviews/:reviewId            平台终审
```

但上述内容仍然是页面级描述，尚不足以指导企业级 AI Native 前端实现。

本补充文档用于进一步定义：

```text
1. AI Native 前端交互范式
2. 页面信息架构
3. 动态工作区机制
4. Generative UI Schema 设计
5. Agent 状态可视化
6. 证据可视化交互
7. AI 审理庭交互
8. AI 评议团交互
9. Human-in-the-loop 审核交互
10. 前端状态管理
11. 前端事件流与实时更新
12. 权限、脱敏、安全与审计
13. 前端测试与验收清单
```

本系统前端不是普通管理后台，不应做成“表格 + 详情 + 聊天框”的简单组合，而应设计成：

```text
AI Native 履约争端审理工作空间
```

用户、商家、审核员不是在操作一堆表单，而是在与 AI 接待官、证据书记官、AI 主审官、AI 评议团和审核辅助官协作完成争端审理。

---

## 1. AI Native 前端设计总原则

### 1.1 以争端案件为中心，而不是以订单为中心

前端所有页面围绕 `FulfillmentDisputeCase` 组织。

禁止设计成：

```text
订单列表
物流列表
售后单列表
发货监控大盘
```

必须设计成：

```text
争端案件列表
争端案件工作台
证据卷宗
审理阶段
审核包
执行结果
```

订单、物流、支付、售后只作为案件证据上下文出现。

### 1.2 以意图输入为入口，而不是以菜单操作为入口

传统后台让用户先选菜单、选类型、填表单。

AI Native 前端应改成：

```text
用户 / 商家描述争端
→ AI 接待官识别争端类型
→ 动态生成补充信息卡片
→ 创建案件
→ 进入案件工作台
```

前端不要求用户一开始准确选择“签收未收到 / 退货掉包 / 破损责任 / 少件错发”，而是允许用户自然语言描述，再由争议接待官生成结构化受理结果。

### 1.3 以动态工作区承载 AI 结果，而不是只展示聊天回答

AI 输出不应只以文本气泡展示。

不同 Agent 的输出应进入不同 UI 容器：

| Agent | 前端呈现 |
|---|---|
| 争议接待官 | 受理分析卡、缺失信息卡、转交说明卡 |
| 证据书记官 | 证据卷宗、时间线、证据矩阵、缺证卡 |
| AI 主审官 | C1-C6 审理阶段、争点、证据分析、规则适用、草案 |
| AI 评议团 | 五类 Critic 质询报告、重大异议、修订建议 |
| 审核辅助官 | 审核问答、证据解释、方案对比 |
| Evaluation Agent | 离线复盘报告、指标趋势、改进建议 |

### 1.4 以可解释状态流为核心，而不是黑盒 AI 结果

前端必须持续告诉用户：

```text
AI 正在做什么
为什么需要这一步
当前用了哪些证据
还缺哪些材料
哪些结论不是最终结论
哪些动作必须人工确认
```

AI 生成的任何结论必须有：

```text
证据引用
规则引用
生成时间
Agent 名称
Prompt / Skill / Model / Profile 版本
置信度
不确定性
非最终提示
```

### 1.5 以人审门控为安全体验，不是阻碍体验

高风险动作不能让用户觉得“系统卡住了”。

前端应把 Human-in-the-loop 展示成清晰的审理节点：

```text
AI 已完成草案
评议团已完成质询
正在等待平台审核员确认
审核员可批准、修改、退回补证、拒绝或升级
```

### 1.6 以协作区分角色视图

同一个案件，不同角色看到不同界面。

| 角色 | 主要关注 |
|---|---|
| 用户 | 我的主张、我要补什么、进度、平台说明 |
| 商家 | 商家主张、需提交材料、质检/发货证明、平台要求 |
| 平台客服 | 案件状态、双方材料、是否需要接管 |
| 平台审核员 | ReviewPacket、AI 草案、评议团异议、RemedyPlan、审核动作 |
| 管理员 | 配置、审计、指标、评估报告 |

---

## 2. 前端信息架构

### 2.1 路由结构

```text
/disputes
  争议办理总览

/disputes/:caseId/intake
  争议接待室

/disputes/:caseId/evidence
  证据书记官室

/disputes/:caseId/hearing
  小法庭

/disputes/:caseId/outcome
  最终结果

/reviews
  平台审核任务列表

/reviews/:reviewId
  平台终审，内含审核辅助官
```

### 2.2 全局布局

不同房间使用不同空间布局，不复用传统后台三栏：

```text
总览：左侧争议订单栏 + 右侧审理游园；
接待室：数字人会客空间 + 对话 + 卷宗贴纸；
证据室：双方提交桌 + 中央共享证据书架；
小法庭：书记官席 + 主审官席 + 双方席位 + 和解桌；
平台终审：动态卷宗认知场 + 审核辅助官。
```

全局只复用品牌栏、角色、传票信箱、案件状态、服务端时钟和人类终审提示。

### 2.3 全局组件

```text
CaseStatusBadge
RiskLevelBadge
AgentRunStatus
EvidenceReference
RuleReference
NonFinalNotice
HumanReviewRequiredBadge
TraceDrawer
VersionSelector
AIConfidenceIndicator
UncertaintyPanel
ActionGuardCard
```

---

## 3. AI Native 交互模式

### 3.1 Intent-first 输入

用于争端发起入口。

用户输入自然语言：

```text
物流显示签收，但我没有收到货。
```

前端交互：

```text
输入框
→ 附件上传
→ 点击“让 AI 接待官分析”
→ 显示结构化受理分析
→ 用户确认或补充信息
→ 创建案件
```

受理分析卡展示：

```text
争端类型：签收未收到
发起方：用户
识别主张：物流显示签收但本人未收到
初步风险：高
缺失信息：快递沟通截图、未收到情况说明
建议：进入证据卷宗构建
```

### 3.2 Dynamic Workspace 动态工作区

前端不为所有场景写死 UI，而是由后端返回 `workspace_schema` 控制当前工作区显示。

示例：

```json
{
  "workspaceType": "DISPUTE_INTAKE",
  "layout": "intake_conversation_room",
  "components": [
    {
      "type": "INTAKE_ANALYSIS_CARD",
      "props": {
        "disputeType": "SIGNED_NOT_RECEIVED",
        "confidence": 0.86
      }
    },
    {
      "type": "MISSING_INFO_FORM",
      "props": {
        "fields": ["delivery_contact_record", "user_statement"]
      }
    }
  ],
  "actions": [
    {
      "actionId": "CONFIRM_CREATE_CASE",
      "label": "确认发起争端",
      "requiresConfirm": true
    }
  ]
}
```

前端只渲染白名单组件，不执行后端返回的任意代码。

### 3.3 Agent Progress Timeline

所有关键 Agent 运行过程用时间线展示：

```text
争议接待官：已识别争端类型
证据书记官：正在构建证据卷宗
AI 主审官：正在归纳争点
AI 主审官：等待用户补证
AI 主审官：正在适用规则
AI 评议团：证据质询员发现重大异议
平台审核员：等待确认
Tool Executor：已执行退款/补发/驳回/通知
```

每个节点包含：

```text
Agent 名称
运行状态
开始时间
结束时间
耗时
输入版本
输出版本
Trace 链接
失败原因
是否需要人工
```

### 3.4 Evidence-linked UI 证据链接交互

所有事实判断必须可以点击跳转到证据。

示例：

```text
AI 主审官判断：
“当前缺少签收照片，因此无法直接确认用户实际收到商品。”

点击“签收照片缺失”
→ 跳转 AI 证据工作室
→ 高亮 MissingEvidence: signed_receipt_photo
→ 展示对应争点 ISS-1
```

### 3.5 Human Override 人类覆盖交互

审核员可以对 AI 输出进行：

```text
接受
修改
驳回
退回补证
升级风控
标记 AI 错误
```

每次修改都必须要求填写理由，并形成审计记录。

---

## 4. Generative UI Schema 设计

### 4.1 设计目标

Generative UI 不是让模型生成 Vue 代码，而是让 Agent 生成受控 UI Schema。

前端只渲染预注册组件。

### 4.2 UI Schema Envelope

```json
{
  "schemaVersion": "frontend-ai-ui-schema-1.0",
  "caseId": "DC-10086",
  "generatedBy": "DisputeIntakeOfficerAgent",
  "generatedAt": "2026-07-02T10:00:00Z",
  "workspaceType": "DISPUTE_INTAKE",
  "layout": "THREE_COLUMN",
  "components": [],
  "actions": [],
  "dataRefs": [],
  "warnings": [],
  "traceId": "TRACE-xxx"
}
```

### 4.3 组件白名单

```text
INTAKE_ANALYSIS_CARD
MISSING_INFO_FORM
TRANSFER_NOTICE_CARD
CASE_SUMMARY_CARD
PARTY_CLAIM_CARD
EVIDENCE_ITEM_CARD
EVIDENCE_TIMELINE
EVIDENCE_MATRIX
MISSING_EVIDENCE_PANEL
CONFLICT_EVIDENCE_PANEL
HEARING_STAGE_TIMELINE
ISSUE_LIST_PANEL
EVIDENCE_GAP_PANEL
RULE_APPLICATION_PANEL
ADJUDICATION_DRAFT_PANEL
DELIBERATION_REPORT_PANEL
CRITIC_REPORT_CARD
REVIEW_PACKET_PANEL
REMEDY_PLAN_CARD
ACTION_GUARD_CARD
REVIEW_DECISION_PANEL
TRACE_DRAWER
```

禁止组件：

```text
任意 HTML 注入组件
任意脚本执行组件
任意远程组件 URL
任意未注册动作按钮
```

### 4.4 动作白名单

```text
SUBMIT_DISPUTE
ADD_EVIDENCE
CONFIRM_CREATE_CASE
REQUEST_MORE_INFO
OPEN_EVIDENCE_WORKSPACE
OPEN_HEARING_COURT
ASK_REVIEW_COPILOT
APPROVE_REVIEW
MODIFY_AND_APPROVE
RETURN_FOR_EVIDENCE
REJECT_RECOMMENDATION
ESCALATE_REVIEW
DOWNLOAD_EVIDENCE
VIEW_TRACE
```

### 4.5 UI Schema 安全约束

```text
组件必须来自白名单。
Action 必须来自白名单。
所有 Action 必须经过前端二次确认规则和后端权限校验。
UI Schema 不得携带脚本。
UI Schema 不得携带未脱敏隐私。
UI Schema 不得覆盖服务端业务状态。
```

---

## 5. 页面一：争端发起入口

### 5.1 页面目标

让用户或商家用自然语言发起履约争端，而不是填写复杂表单。

### 5.2 主要用户路径

```text
进入争端发起页
→ 输入争端描述
→ 上传初始证据
→ AI 接待官分析
→ 展示受理结果
→ 若缺信息则补充
→ 若不构成争端则不予受理并留档
→ 若构成争端则创建案件
```

### 5.3 页面布局

```text
顶部：页面标题 + 系统边界提示
中部左侧：争端描述输入 + 引导问题
中部右侧：AI 接待官分析卡
底部：附件上传 + 提交动作
```

### 5.4 核心组件

#### DisputeIntentInput

字段：

```text
description
orderReference
afterSalesReference
logisticsReference
initiatorRole
attachments
```

交互特性：

```text
支持长文本输入
支持场景示例快捷填充
支持附件拖拽上传
支持输入过程中本地提示
```

#### IntakeAnalysisCard

展示：

```text
是否构成争端
争端类型
发起方主张
请求处理结果
缺失信息
初步风险
建议下一步
置信度
```

#### MissingInitialFieldsForm

根据 AI 接待官输出动态生成。

示例字段：

```text
快递沟通截图
未收到情况说明
退货前照片
商家质检照片
发货前序列号
```

### 5.5 状态设计

```text
IDLE
DRAFTING
ANALYZING
NEED_MORE_INFO
NOT_ADMISSIBLE
READY_TO_CREATE
CREATING_CASE
CREATED
FAILED
```

### 5.6 API 交互

```text
POST /internal/agents/intake/analyze
POST /api/disputes
POST /api/disputes/{caseId}/evidence
```

### 5.7 空状态与失败态

```text
非争端请求：显示“该请求不属于履约争端，本次申请未受理并已留档。”
订单不存在：提示补充正确订单号。
证据上传失败：允许重试，不阻塞文本提交。
AI 分析失败：允许人工提交争端草案。
```

---

## 6. 页面二：争端案件列表

### 6.1 页面目标

展示当前用户/商家/审核员有权限访问的争端案件，不展示全量订单。

### 6.2 列表字段

```text
caseNo
disputeType
initiatorRole
oppositeParty
caseStatus
riskLevel
currentStage
updatedAt
pendingAction
deadline
```

### 6.3 筛选条件

```text
案件状态
争端类型
风险等级
待我处理
即将超时
是否进入评议团
是否等待补证
是否等待审核
```

### 6.4 AI Native 能力

列表顶部提供自然语言筛选：

```text
找出所有等待我补证的签收未收到案件
找出高风险且评议团有重大异议的案件
找出即将超时的退货掉包争议
```

该能力必须转换为受控 filter schema，不允许直接拼 SQL。

---

## 7. 页面三：争端案件工作台

### 7.1 页面目标

提供案件总览、当前阶段、下一步动作和关键风险。

### 7.2 页面布局

```text
顶部：案件状态 Header
左侧：案件阶段导航
中间：案件摘要与双方主张
右侧：下一步动作与 AI 协作面板
```

### 7.3 核心组件

```text
CaseHeader
CaseStageStepper
PartyClaimsPanel
RiskSummaryCard
PendingActionCard
NextBestActionPanel
CaseTimelinePreview
EvidenceShortcutCard
HearingShortcutCard
ReviewStatusCard
```

### 7.4 CaseStageStepper

阶段：

```text
提交争端
受理分析
证据卷宗
审理路由
简易审理 / 完整审理
AI 评议团
执行方案
平台审核
执行
关闭
```

每个阶段展示：

```text
状态
负责人
开始时间
结束时间
是否 AI 参与
是否人工参与
Trace 链接
```

### 7.5 NextBestActionPanel

按角色展示下一步：

| 角色 | 示例动作 |
|---|---|
| 用户 | 补充快递沟通截图 |
| 商家 | 上传发货凭证 |
| 审核员 | 审核 AI 草案 |
| 客服 | 接管异常案件 |

---

## 8. 页面四：AI 证据工作室

### 8.1 页面目标

把混乱的材料转化为可审理的证据卷宗。

这是系统最能体现 AI Native 证据工程能力的页面。

### 8.2 页面布局

```text
左侧：证据目录与筛选
中间：证据详情 / 时间线 / 矩阵
右侧：证据书记官摘要与问答
```

### 8.3 核心组件

```text
DossierVersionSelector
EvidenceCatalog
EvidencePreview
ParsedContentViewer
EvidenceTimeline
PartyClaimsPanel
MissingEvidencePanel
EvidenceConflictPanel
ClaimIssueEvidenceMatrix
EvidenceReliabilityTag
EvidenceCitationDrawer
EvidenceClerkChatPanel
```

### 8.4 EvidenceCatalog

字段：

```text
evidenceId
evidenceType
sourceParty
sourceSystem
submittedAt
parsedStatus
reliability
linkedIssues
privacyLevel
```

证据类型：

```text
平台订单记录
物流轨迹
签收证明
支付记录
售后记录
聊天记录
发货凭证
退货物流
质检照片
用户上传截图
商家上传视频
```

### 8.5 EvidenceTimeline

时间线节点：

```text
下单
支付
发货
揽收
派送
签收
发起售后
提交证据
商家拒绝
用户补证
平台审理
```

交互：

```text
点击节点 → 高亮相关证据
筛选证据类型
显示冲突节点
显示缺失节点
```

### 8.6 Claim-Issue-Evidence Matrix

矩阵维度：

```text
PartyClaim
Issue
SupportingEvidence
ContradictingEvidence
MissingEvidence
CurrentStatus
```

交互能力：

```text
点击主张 → 查看对应争点
点击争点 → 查看支持/反驳证据
点击缺证 → 发起补证请求
点击冲突 → 跳转证据冲突面板
```

### 8.7 证据书记官问答

支持问题：

```text
这份证据支持哪个主张？
哪些证据互相冲突？
当前最关键的缺失证据是什么？
这份截图是否已经解析？
哪些证据需要人工重点查看？
```

输出必须包含证据引用，不得输出责任判断。

### 8.8 证据安全

```text
用户看不到商家内部不可见材料。
商家看不到用户隐私材料。
审核员按权限查看完整审核材料。
原始证据下载必须审计。
敏感字段默认脱敏。
```

---

## 9. 页面五：AI 审理庭

### 9.1 页面目标

可视化 AI 主审官在 Workflow 控制下的 C1-C6 审理过程。

### 9.2 页面布局

```text
顶部：审理状态与风险
左侧：C1-C6 阶段导航
中间：当前阶段详细结果
右侧：AI 主审官解释、证据引用、下一步
底部：评议团与草案修订记录
```

### 9.3 C1-C6 阶段展示

#### C1 争点归纳

展示：

```text
争点问题
争点类型
关联主张
所需证据
优先级
当前状态
```

#### C2 缺证识别

展示：

```text
缺失证据
对应争点
目标提交方
重要程度
缺失原因
建议截止时间
```

#### C3 补证请求

展示：

```text
补证对象
补证材料清单
中立说明文案
截止时间
提交状态
提醒记录
```

#### C4 证据交叉核验

展示：

```text
支持性证据
反驳性证据
冲突证据
待人工核验点
置信度
```

#### C5 规则适用

展示：

```text
适用规则
规则版本
满足条件
不满足条件
待证条件
规则引用
```

#### C6 裁决草案

展示：

```text
事实认定
证据评价
规则适用
建议处理方向
置信度
审核员关注点
非最终裁决提示
```

### 9.4 AI 主审官输出标识

每块 AI 输出必须显示：

```text
AI 主审官生成
生成时间
模型版本
Prompt 版本
Skill 版本
Profile 版本
证据引用
规则引用
非最终结论
```

### 9.5 草案修订记录

若评议团或审核员要求修订：

```text
草案版本 v1
评议团异议
修订原因
草案版本 v2
差异对比
```

---

## 10. 页面六：AI 评议团展示

### 10.1 页面目标

让平台审核员清晰看到五类 Critic 对 AI 主审官草案的质询结果。

### 10.2 展示原则

```text
重大异议优先展示。
少数严重意见不能被隐藏。
不同 Critic 观点要分栏展示。
共识、异议、建议修订分开显示。
```

### 10.3 组件

```text
DeliberationTriggerCard
CriticReportTabs
MajorRiskPanel
ConsensusPanel
DisagreementPanel
RecommendedRevisionPanel
ReviewerAttentionPanel
```

### 10.4 五类 Critic Tab

```text
证据质询员
规则质询员
风险质询员
执行方案质询员
公平性/一致性质询员
```

每个 Tab 展示：

```text
严重度
核心发现
阻断性问题
建议修订
引用证据/规则
是否需要人工重点看
```

### 10.5 严重度视觉规范

```text
INFO：普通提示
LOW：轻微风险
MEDIUM：需要关注
HIGH：高风险
BLOCKER：阻断，必须人工处理或修订
```

---

## 11. 页面七：平台审核台

### 11.1 页面目标

为平台审核员提供最终确认空间。

审核台不是简单审批按钮页，而是“人类最终责任节点”。

### 11.2 页面布局

```text
左侧：ReviewPacket 目录
中间：审核主体内容
右侧：审核辅助官 + 审核动作面板
```

### 11.3 ReviewPacket 展示

必须包含：

```text
案件摘要
双方主张
证据卷宗摘要
争点列表
证据缺口
证据冲突
规则适用
AI 主审官草案
AI 评议团报告
RemedyPlan
Approval Policy 说明
风险提示
```

### 11.4 审核动作

```text
批准
修改后批准
退回补证
拒绝 AI 建议
升级风控/法务/高级审核
```

每个动作都必须：

```text
二次确认
显示将执行的具体动作
显示 action_hash
要求填写审核理由
生成 HumanReviewRecord
```

### 11.5 审核辅助官

支持问题：

```text
为什么建议这个方案？
哪些证据最关键？
评议团提出了哪些风险？
如果退回补证，需要补哪些材料？
这个案件和同类历史案件是否一致？
```

输出要求：

```text
必须引用证据或规则
区分事实、推断、建议
不得替审核员做决定
不得触发执行
```

---

## 12. 前端实时状态与事件流

### 12.1 事件来源

```text
Workflow 状态变化
Agent Run 状态变化
证据解析状态变化
补证提交状态变化
评议团运行状态
审核任务状态
执行动作状态
```

### 12.2 事件类型

```text
CASE_STATUS_CHANGED
AGENT_RUN_STARTED
AGENT_RUN_STREAMING
AGENT_RUN_COMPLETED
AGENT_RUN_FAILED
DOSSIER_VERSION_CREATED
EVIDENCE_PARSE_COMPLETED
HEARING_STAGE_COMPLETED
HUMAN_INTERRUPT_CREATED
DELIBERATION_STARTED
DELIBERATION_COMPLETED
REVIEW_PACKET_CREATED
REVIEW_DECISION_SUBMITTED
ACTION_EXECUTION_STARTED
ACTION_EXECUTION_COMPLETED
ACTION_EXECUTION_FAILED
```

### 12.3 前端处理原则

```text
页面刷新不丢状态。
事件乱序时以后端 query 结果为准。
关键动作必须主动 refetch。
流式文本只作展示，不作为业务事实。
Agent 最终结构化输出才可进入正式 UI 区块。
```

### 12.4 推送方式建议

```text
短期：SSE
中期：WebSocket
兜底：轮询
```

对于企业级可靠性，关键状态不得只依赖前端流式消息，必须可通过 REST 查询恢复。

---

## 13. 前端状态管理设计

### 13.1 Pinia Store 划分

```text
useAuthStore
useDisputeListStore
useDisputeCaseStore
useEvidenceDossierStore
useHearingStore
useReviewStore
useAgentRunStore
useRealtimeEventStore
useUiWorkspaceStore
```

### 13.2 useDisputeCaseStore

状态：

```text
caseDetail
caseStatus
currentStage
riskLevel
partyClaims
pendingActions
lastUpdatedAt
```

### 13.3 useEvidenceDossierStore

状态：

```text
dossierVersion
evidenceCatalog
timeline
matrix
missingEvidence
conflicts
selectedEvidenceId
```

### 13.4 useHearingStore

状态：

```text
hearingState
currentStage
stageResults
adjudicationDraft
deliberationReport
draftRevisions
```

### 13.5 useReviewStore

状态：

```text
reviewTask
reviewPacket
remedyPlan
approvalPolicy
reviewDecisionDraft
copilotMessages
```

### 13.6 useAgentRunStore

状态：

```text
agentRuns
runningAgents
agentRunLogs
traceIds
streamingOutputs
```

---

## 14. 前端组件架构

### 14.1 目录建议

```text
src/
├── pages/
│   ├── disputes/
│   │   ├── NewDisputePage.vue
│   │   ├── DisputeListPage.vue
│   │   ├── DisputeWorkspacePage.vue
│   │   ├── EvidenceStudioPage.vue
│   │   └── HearingCourtPage.vue
│   └── reviews/
│       ├── ReviewTaskListPage.vue
│       └── ReviewConsolePage.vue
├── components/
│   ├── ai/
│   ├── dispute/
│   ├── evidence/
│   ├── hearing/
│   ├── deliberation/
│   ├── review/
│   ├── trace/
│   └── common/
├── stores/
├── api/
├── schemas/
├── router/
└── utils/
```

### 14.2 AI 组件

```text
AgentRunStatus.vue
AIConfidenceIndicator.vue
NonFinalNotice.vue
UncertaintyPanel.vue
TraceDrawer.vue
GeneratedAtMeta.vue
PromptSkillVersionTag.vue
```

### 14.3 证据组件

```text
EvidenceCatalog.vue
EvidencePreview.vue
EvidenceTimeline.vue
EvidenceMatrix.vue
EvidenceReference.vue
EvidenceConflictPanel.vue
MissingEvidencePanel.vue
EvidenceReliabilityTag.vue
```

### 14.4 审理组件

```text
HearingStageStepper.vue
IssueListPanel.vue
EvidenceGapPanel.vue
EvidenceRequestPanel.vue
CrossCheckPanel.vue
RuleApplicationPanel.vue
AdjudicationDraftPanel.vue
DraftRevisionDiff.vue
```

### 14.5 评议团组件

```text
DeliberationTriggerCard.vue
CriticReportTabs.vue
CriticReportCard.vue
MajorRiskPanel.vue
ConsensusPanel.vue
DisagreementPanel.vue
RecommendedRevisionPanel.vue
```

### 14.6 审核组件

```text
ReviewPacketPanel.vue
RemedyPlanCard.vue
ApprovalPolicyCard.vue
ReviewDecisionPanel.vue
ActionGuardCard.vue
ReviewCopilotPanel.vue
```

---

## 15. 前端权限与脱敏

### 15.1 角色权限

| 角色 | 权限 |
|---|---|
| 用户 | 查看自己发起或相关案件，提交证据，查看面向用户的说明 |
| 商家 | 查看与自身店铺相关案件，提交商家证据，查看平台要求 |
| 客服 | 查看授权队列案件，辅助沟通，转人工 |
| 审核员 | 查看 ReviewPacket，执行审核动作 |
| 管理员 | 查看配置、审计、评估，不直接改变单案裁决 |

### 15.2 脱敏规则

```text
手机号：默认中间脱敏
地址：按角色展示部分或完整
支付信息：只展示必要金额和流水后缀
身份证/证件：默认不展示
聊天记录：按角色过滤
商家内部质检备注：仅审核员可见
```

### 15.3 前端安全

```text
前端不保存模型密钥。
前端不保存服务间 token。
前端不信任 UI Schema 中的任意代码。
所有敏感操作必须后端校验。
所有下载必须使用短期签名 URL。
```

---

## 16. 前端 API 适配

### 16.1 API 模块

```text
disputeApi.ts
evidenceApi.ts
hearingApi.ts
reviewApi.ts
agentApi.ts
realtimeApi.ts
```

### 16.2 API 约定

所有 API 响应包含：

```text
requestId
traceId
timestamp
data
```

错误响应：

```json
{
  "requestId": "REQ-xxx",
  "traceId": "TRACE-xxx",
  "errorCode": "REVIEW_PACKET_STALE",
  "message": "审核包已过期，请刷新后重试。",
  "details": {}
}
```

### 16.3 前端错误处理

```text
权限错误：展示无权限页或隐藏动作
状态冲突：提示刷新
过期审核包：强制重新加载 ReviewPacket
证据解析失败：展示 parser_warning
Agent 失败：展示安全降级与人工处理入口
```

---

## 17. 前端测试方案

### 17.1 单元测试

```text
组件渲染
状态转换
权限显示
脱敏显示
UI Schema 渲染
Action 按钮显示规则
```

### 17.2 交互测试

```text
争端提交
证据上传
卷宗版本切换
矩阵点击跳转
审理阶段切换
评议团报告展开
审核动作二次确认
审核辅助问答
```

### 17.3 E2E 测试

```text
非争端请求转交
签收未收到完整审理
退货掉包触发评议团
平台审核员退回补证
审核员批准后执行
审核包过期重试
```

### 17.4 安全测试

```text
UI Schema 注入
XSS 证据文本
越权 caseId
越权下载证据
无权限审核动作
重复点击审批按钮
```

---

## 18. 前端验收清单

### 18.1 产品边界

- [ ] 前端没有订单中心。
- [ ] 前端没有物流监控大盘。
- [ ] 前端没有泛售后工作台。
- [ ] 页面以争端案件为中心。

### 18.2 AI Native 交互

- [ ] 支持自然语言争端发起。
- [ ] 支持 AI 接待官受理分析卡。
- [ ] 支持动态缺失信息补充表单。
- [ ] 支持 AI 证据工作室。
- [ ] 支持 AI 审理庭 C1-C6 展示。
- [ ] 支持 AI 评议团报告展示。
- [ ] 支持审核辅助官问答。
- [ ] 支持 Agent 运行状态展示。
- [ ] 支持 Trace 查看入口。

### 18.3 证据交互

- [ ] 原始证据、解析结果、AI 摘要视觉区分。
- [ ] 证据可按来源、类型、争点筛选。
- [ ] 时间线可点击跳转证据。
- [ ] 矩阵可点击跳转争点和证据。
- [ ] 缺失证据可触发补证动作。
- [ ] 冲突证据可高亮对比。

### 18.4 审理交互

- [ ] C1-C6 阶段完整展示。
- [ ] 每个阶段显示输入版本和输出版本。
- [ ] AI 草案标记非最终裁决。
- [ ] 草案显示证据引用和规则引用。
- [ ] 草案显示置信度和不确定性。
- [ ] 草案修订记录可对比。

### 18.5 人审交互

- [ ] ReviewPacket 冻结版本展示。
- [ ] 评议团重大异议醒目展示。
- [ ] 审核动作必须二次确认。
- [ ] 审核动作展示 action_hash。
- [ ] 审核理由必填。
- [ ] 审核辅助官不得触发审批。

### 18.6 安全与权限

- [ ] 用户/商家只能看到授权案件。
- [ ] 敏感信息按角色脱敏。
- [ ] 证据下载使用短期 URL。
- [ ] UI Schema 不执行任意代码。
- [ ] 前端不持有模型密钥。
- [ ] 越权操作后端拒绝，前端提示。

---

## 19. 最终前端定位总结

本系统前端不是传统后台页面，也不是简单聊天机器人，而是：

```text
AI Native 履约争端审理工作空间
```

它的核心体验是：

```text
用户用自然语言发起争端；
证据书记官把材料组织成证据卷宗；
AI 主审官把争端拆成争点、缺证、规则和草案；
AI 评议团对高风险草案提出多维质询；
平台审核员在审核台完成人审确认；
所有 AI 输出都可解释、可引用、可追溯、可人工覆盖。
```

前端必须体现三种先进性：

```text
1. 交互先进性：Intent-first、Dynamic Workspace、Generative UI Schema。
2. 审理先进性：证据矩阵、C1-C6 可视化、评议团异议展示。
3. 工程先进性：权限脱敏、状态流、Trace、Schema 白名单、HITL Action Guard。
```

---

## 20. 房间式数字人页面体系（最终覆盖版）

本节覆盖前文的传统三栏工作台和独立评议团页面建议。最终前端由“争议总览 + 三个业务房间 + 平台终审 + 结果 + 传票信箱”组成。

### 20.1 最终路由

```text
/disputes
/disputes/:caseId/intake
/disputes/:caseId/evidence
/disputes/:caseId/hearing
/disputes/:caseId/outcome
/reviews
/reviews/:reviewId
```

### 20.2 全局视觉语言

```text
母题：会思考的轻法庭乐园；
颜色：暖白、天蓝、珊瑚橙、嫩芽绿、柔和紫；
形态：圆角、柔和阴影、可翻阅卷宗卡、印章、传票、小法槌；
数字人：2D 卡通角色；
禁止：黑金庄严风、传统电商后台、密集通用表格、永久侧边菜单。
```

数字人状态：

```text
IDLE
LISTENING
THINKING
SPEAKING
COMPLETED
HANDOFF
ERROR
```

所有动画支持 `prefers-reduced-motion`。

### 20.3 页面一：争议办理总览

桌面布局：

```text
顶部：品牌、角色、全局搜索、传票信箱；
左侧：当前角色涉及的全部争议订单；
右侧：选中案件的审理游园状态路线；
底部或浮层：当前数字人、下一步动作和剩余时效。
```

左侧卡片展示：

```text
caseNo
orderReference
sourceType
disputeType
caseStatus
currentRoom
pendingParty
deadlineAt
remainingTime
riskLevel
unreadCount
```

点击卡片只切换右侧旅程；点击路线节点或“进入当前房间”才导航。总览不显示普通订单。

Figma 提示词：

```text
设计一个 1440px 明亮卡通 AI 履约争端总览。左侧约 340px 是有状态和倒计时的争议订单栏，右侧是可探索的审理游园地图，节点包括接待室、证据室、小法庭、平台终审和结果。右上角有会弹出盖章传票的卡通信箱。展示外部导入和接待官创建的多种状态争议订单。不要传统表格、永久后台侧栏和黑金法庭。
```

### 20.4 页面二：争议接待室

主要区域：

```text
争议接待官数字人；
自然语言对话；
订单/售后/物流引用贴纸；
双方主张贴纸；
诉求与期望结果；
初始风险信号；
受理建议；
缺失信息补充；
发起方最终确认。
```

受理后显示“已上报”动画、相对方传票状态和证据书记官入口。不予受理时显示留档说明，不显示普通售后入口。

Figma 提示词：

```text
设计一个轻松的数字人会客室。争议接待官是友好、有活力的 2D 卡通业务员，通过对话逐步把订单引用、双方主张、诉求、风险和受理建议变成可确认的彩色卷宗贴纸。受理成功后出现盖章“已上报”和通往证据书房的门。明确 AI 只做受理建议。
```

### 20.5 页面三：证据书记官室

顶部固定：

```text
PT2H 服务端倒计时；
USER 完成状态；
MERCHANT 完成状态；
当前卷宗版本；
证据书记官状态。
```

空间布局：

```text
左侧用户提交桌；
右侧商家提交桌；
中央共享证据书架；
底部对话与多模态上传托盘。
```

证据卡必须区分目录信息、授权原件、解析文本、AI 摘要和可信度：

```text
VERIFIED
PLAUSIBLE
SUSPICIOUS
REJECTED
NEEDS_HUMAN_REVIEW
```

Figma 提示词：

```text
设计一个卡通证据书房。顶部是醒目的两小时倒计时和双方完成进度；用户与商家各有提交桌，中间是共享证据书架。图片、视频和文件变成可翻阅线索卡，显示来源、时间、解析、可见性和五级可信度。证据书记官用对话解释缺口和冲突，但不宣布责任。
```

### 20.6 页面四：小法庭

小法庭同时服务用户、商家和只读旁观的审核员：

```text
证据书记官席：陈列双方证据；
AI 主审官席：主持争点、质证、规则适用；
用户与商家发言席；
三轮轨迹；
PT3H 倒计时；
补充证据入口；
版本化和解桌；
按需出现的 AI 评议席。
```

用户和商家视图不显示审核辅助官。审核员在 ReviewPacket 冻结前没有批准按钮。

Figma 提示词：

```text
设计一个活泼但可信的小法庭舞台。书记官先陈列双方证据，AI 主审官主持固定三轮陈述；每轮双方提交或 20 分钟到期后自动封存。前三轮只归纳争点、证据解释和下一轮问题，第三轮后生成确定的裁决执行方案草案。顶部显示三小时倒计时，侧面显示三轮陈述轨迹和双方席位；中央有和解桌，双方确认同一方案后点亮。AI 评审团只在最终方案后从评议席出现，低于 80 分要求法官最多重生成 2 次。所有 AI 结论标注非最终，人类最终落槌。
```

必须设计以下状态：

```text
庭审进行中；
等待一方陈述；
本轮自动提交中；
和解待一方确认；
和解双确认；
最终方案后评审团介入；
最终方案后评审团跳过；
三轮耗尽；
PT3H 到期并强制草案；
等待平台终审。
```

### 20.7 页面五：平台终审

采用“会呼吸的卷宗”认知地图：

```text
案件核心争点；
双方主张；
关键证据；
规则适用；
裁决草案；
按需评议结果；
Remedy Plan；
Approval Policy；
审核辅助官；
人类审核动作。
```

Figma 提示词：

```text
设计一个明亮的动态卷宗认知场。争点位于中心，主张、证据、规则、草案和评议结果围绕它排列。审核辅助官是仅审核员可见的卡通数字人，负责解释而非审批。最终动作区域明确显示 packet 版本、action hash、理由输入和人类落槌确认。
```

### 20.8 页面六：结果

```text
人类最终决定；
与 AI 草案的差异；
实际执行动作；
执行进度；
关键证据与规则引用；
不可变案件时间线。
```

Figma 提示词：

```text
设计一个轻法庭裁决结果页，以盖章卷轴和执行旅程呈现。人类最终决定与 AI 草案必须视觉分离，展示执行状态、关键依据和可追溯时间线，保持温暖、清晰、不庆祝任何一方失败。
```

### 20.9 传票信箱

传票信箱是全局抽屉，也可展开为完整通知视图：

```text
争议传票；
证据室开放；
举证临期；
开庭；
补证；
和解确认；
平台终审；
最终结果；
执行完成。
```

点击消息进入对应案件房间。不同角色只看到脱敏后的授权消息。

Figma 提示词：

```text
设计一个卡通小信箱组件，未读时弹出信封和盖章传票，展开后按案件分组展示时间、类型、剩余时效和深链按钮。它是争议状态流提醒，不是短信或营销消息中心。
```

### 20.10 实时与状态管理

```text
REST：提交消息、证据、确认和审核动作；
SSE：房间事件、Agent 状态、时钟状态和通知；
Last-Event-ID：断线续传；
Pinia：dispute、room、evidence、hearing、notification、review、agentRun。
```

浏览器只显示服务端截止时间，不在本地触发封卷、开庭或裁决。

### 20.11 Figma 到 Vue 的验收流程

每个页面必须按以下顺序：

```text
读取具体 Figma node；
获取结构化设计上下文；
获取节点截图；
记录路由、角色和组件映射；
实现 Vue；
使用相同视口截图对比；
修正布局、字体、颜色、圆角和溢出；
通过桌面、移动和减少动画验收。
```
