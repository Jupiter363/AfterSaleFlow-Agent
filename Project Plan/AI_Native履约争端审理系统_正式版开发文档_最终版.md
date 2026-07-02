# AI Native 履约争端审理系统：正式版开发文档（最终版）

> 文档性质：企业级目标态架构设计、技术执行契约、Agent Harness 工程主控文档  
> 适用对象：架构设计文档、技术方案文档、接口文档、测试文档、验收清单的后续统一改造  
> 当前任务边界：仅优化架构与技术文档，不修改代码、数据库、接口、目录、依赖、Docker、CI/CD  
> 核心定位：只处理用户或商家主动发起的履约争端，不建设订单中心、物流监控或泛售后工作台  
> API 命名原则：正式版接口不使用 `/v2`、`/v3` 等版本路径，统一使用生产语义路径，如 `/api/disputes`、`/internal/agents/...`

---

## 0. 文档定位

本文档用于指导 Codex 对现有架构文档、技术文档和验收文档进行正式版重构。本文档不是代码实现说明，不要求立即修改代码；它是后续代码改造前的目标态工程契约。

本系统最终定位为：

```text
AI Native 履约争端审理系统
```

产品化表达：

```text
AI 履约争端审理庭
AI Native Fulfillment Dispute Hearing System
AI Fulfillment Dispute Court
```

一句话定义：

```text
系统以用户或商家主动发起的履约争端为唯一入口，以 FulfillmentDisputeCase 为唯一主对象，通过争议接待官、证据书记官、AI 主审官、按需 AI 评议团、审核辅助官和离线复盘官协作，在 Workflow 和 Human Review 门控下完成履约争端从受理、举证、审理、评议、审核、执行到复盘的全流程闭环。
```

---

## 1. 产品定位与系统边界

### 1.1 为什么要收敛为履约争端审理系统

系统不应做成大而全的订单中心，也不应做成泛售后工作台。泛化会稀释 Agent 技术亮点，使系统变成普通 CRUD 后台加聊天机器人。

本系统真正适合 Agent 的业务价值在于：

```text
双方主张冲突
证据不完整
证据互相矛盾
责任边界模糊
规则适用复杂
执行动作高风险
平台需要审理和确认
```

这些问题不是单纯查库、规则判断或流程自动化可以完全解决的，正适合通过 Agent Harness、证据工程、受控审理、多 Agent 评议和人审门控体现技术水平。

### 1.2 系统只处理什么

本系统只处理已经形成或可能形成双方冲突的履约争端。

| 场景 | 争端本质 | 是否进入本系统 |
|---|---|---|
| 物流显示签收，用户称未收到 | 用户主张与物流记录冲突 | 是 |
| 商家称用户退回商品被掉包 | 用户退货主张与商家质检冲突 | 是 |
| 用户称商品破损，商家称用户使用后损坏 | 损坏责任边界不清 | 是 |
| 用户称少件/错发，商家称发货无误 | 发货证据与收货主张冲突 | 是 |
| 商家拒绝退款，用户不认可 | 售后处理意见冲突 | 是 |
| 商家称影响二次销售，用户不认可 | 商品状态认定冲突 | 是 |
| 高价值退款或补发争议 | 高风险权益与成本冲突 | 是 |
| 用户只问订单到哪了 | 无双方争端 | 否 |
| 用户只催发货 | 普通履约请求 | 否 |
| 用户只查退款进度 | 普通售后查询 | 否 |

### 1.3 系统明确不做什么

本系统不做以下能力：

```text
全量订单中心
普通订单详情页
物流监控大盘
发货时效运营看板
普通催发货
普通查物流
普通退款进度查询
无争议退款/退货
库存调度
仓储作业
配送调度
商家经营分析
通用客服机器人
```

边界原则：

```text
订单不是主对象，订单是证据上下文。
物流不是产品入口，物流是争端证据。
售后不是泛工作台，售后争端才是系统主场。
```

### 1.4 非争端请求处理原则

不构成履约争端的请求应进入 `TRANSFERRED` 终态，并给出转交说明。

```text
非争端请求
→ 争议接待官判断不予受理
→ 生成转交普通售后/客服说明
→ 本系统结束
```

禁止在本系统内继续承接普通查单、普通催发货、普通进度查询等流程。

---

## 2. 架构总原则

### 2.1 四条红线

```text
Agent 不最终裁决。
Workflow 不承载开放式认知。
Human Review 不可绕过。
Tool Executor 不接受未审批动作。
```

### 2.2 六个基本分工

| 层 | 负责什么 | 不负责什么 |
|---|---|---|
| Workflow | 状态、流程、等待、超时、恢复、审计 | 语义理解、证据推理 |
| Agent | 非结构化理解、证据分析、草案生成 | 最终裁决、直接执行 |
| Harness | 上下文、记忆、工具、权限、Guardrail、Trace | 业务事实源 |
| Skill | 场景化审理方法和证据模板 | 状态推进、执行动作 |
| Human Review | 高风险动作最终确认 | 自动化工具执行 |
| Tool Executor | 执行已审批确定性动作 | 判断责任、自由决策 |

### 2.3 Agent 不是流程节点，而是受控认知循环

系统不把 C1-C6 都定义为独立 Agent，而是定义为 AI 主审官在 Workflow 控制下执行的审理 Stage：

```text
C1 争点归纳 Stage
C2 缺证识别 Stage
C3 补证请求 Stage
C4 证据交叉核验 Stage
C5 规则适用 Stage
C6 草案生成 Stage
```

每个 Stage 都由以下组合完成：

```text
DisputeHearingWorkflow
+ AI Presiding Judge Agent
+ Stage-specific Skill
+ Context Builder
+ Tool Gateway
+ Output Schema Validator
+ Guardrail Checker
+ Trace Recorder
```

### 2.4 按需多 Agent，而非全流程多 Agent

AI 评议团只在高风险、低置信度、重大证据冲突、规则适用不确定或审核员要求复核时启动。

```text
普通案件：争议接待官 + 证据书记官 + 简易审理即可。
复杂案件：AI 主审官完成 C1-C6。
高风险案件：AI 主审官 + AI 评议团质询。
```

### 2.5 业务事实源原则

```text
PostgreSQL + Workflow State = 业务事实源
Evidence 原件与快照 = 证据事实源
Agent Memory = 上下文增强，不是事实源
Elasticsearch = 可重建检索投影，不是事实源
Redis = 短期缓存/锁，不是事实源
```

---

## 3. 总体技术架构

### 3.1 总体架构图

```text
用户 / 商家
    ↓
争端发起入口
    ↓
Dispute Intake Officer Agent
争议接待官：判断是否构成履约争端
    ↓
FulfillmentDisputeCase
创建履约争端案件
    ↓
Evidence Clerk Agent
证据书记官：构建 EvidenceDossier
    ↓
Admissibility & Hearing Router
受理 / 简易审理 / 完整审理分流
    ↓
┌───────────────────────────────┐
│ 路径一：TRANSFERRED             │
│ 不予受理 / 转普通售后            │
└───────────────────────────────┘

┌───────────────────────────────┐
│ 路径二：SIMPLE_HEARING          │
│ 规则明确、证据充分、风险较低      │
└───────────────────────────────┘

┌───────────────────────────────┐
│ 路径三：FULL_HEARING            │
│ AI 主审官 C1-C6 + 可选评议团      │
└───────────────────────────────┘
    ↓
Remedy Planner
生成执行方案
    ↓
Approval Policy Engine
审批策略与风险分级
    ↓
Platform Human Review + Review Copilot
平台审核员最终确认
    ↓
Tool Executor
执行已审批动作
    ↓
Case Closure
案件闭环、审计、归档
    ↓
Evaluation Agent
离线复盘、测试集沉淀、Prompt/Skill 优化建议
```

### 3.2 服务拓扑

```text
Browser
  │
  ▼
Nginx
  │
  ▼
Frontend Vue App
  │
  ▼
Java API Service
  ├── PostgreSQL
  ├── Redis
  ├── MinIO
  ├── Elasticsearch
  ├── Temporal Server / Worker
  ├── OCR / Evidence Parser Service
  └── Python Agent Service
        ├── Agent Runtime Harness
        ├── Specialist Agents
        ├── Skill Library
        ├── Tool Gateway Client
        ├── LiteLLM
        └── Langfuse
```

### 3.3 技术栈

| 领域 | 技术 |
|---|---|
| 前端 | Vue 3、Vite、TypeScript、Pinia、Element Plus、Vitest |
| 确定性业务服务 | Java 21、Spring Boot 3、Maven、Spring Security |
| Workflow | Temporal |
| Agent 服务 | Python 3.12、FastAPI、Pydantic、LangGraph |
| 模型网关 | LiteLLM |
| Agent 观测 | Langfuse |
| 证据解析 | OCR / 文档解析组件、FastAPI |
| 业务数据库 | PostgreSQL、Flyway |
| 缓存与锁 | Redis |
| 证据对象存储 | MinIO |
| 检索投影 | Elasticsearch |
| 本地部署 | Docker Compose、Nginx |

### 3.4 服务责任边界

#### Java API Service

负责确定性业务与业务事实。

```text
FulfillmentDisputeCase 聚合根
身份、RBAC、数据范围权限
案件状态合法性
Evidence 元数据和版本记录
ReviewPacket 生成与冻结
Approval Policy Engine
Remedy Planner
Tool Executor
HumanReviewRecord
ActionRecord
AuditLog
Temporal Worker/Activity 适配
```

Java Service 不负责开放式自然语言推理。

#### Python Agent Service

负责 Agent Runtime Harness 和认知型任务。

```text
Agent Runtime Harness
Dispute Intake Officer Agent
Evidence Clerk Agent
AI Presiding Judge Agent
AI Deliberation Panel
Review Copilot Agent
Evaluation Agent
Skill Loader
Context Builder
Tool Gateway Client
Output Validator
Guardrail Checker
Trace Reporter
```

Python Agent Service 不持有最终业务状态，不直连生产业务库，不执行退款、补发、驳回或关闭售后。

#### Evidence Parser Service

负责证据派生处理。

```text
图片 OCR
PDF / Word / Excel 文档解析
图片元数据提取
视频关键帧抽取
证据脱敏
文件哈希
重复证据检测
```

解析结果是证据派生物，不替代原件，不产生责任判断。

#### Temporal

负责长流程可靠执行。

```text
FulfillmentDisputeWorkflow
DisputeHearingWorkflow
DeliberationPanelWorkflow
HumanReviewWorkflow
ExecutionWorkflow
补证等待
人审等待
定时器
重试
恢复
```

#### 数据组件

```text
PostgreSQL：业务事实、版本、审批、审计
MinIO：原始证据和派生文件
Elasticsearch：案件与证据搜索投影，不作为事实源
Redis：短期缓存、限流、幂等锁，不保存长期业务事实
Langfuse：Agent Trace，不作为业务事实源
```

---

## 4. 领域对象模型

### 4.1 主对象：FulfillmentDisputeCase

履约争端案件是系统唯一主对象。

字段建议：

```text
case_id
case_no
initiator_role
user_id
merchant_id
order_id
after_sales_id
logistics_id
dispute_type
case_status
risk_level
current_stage
admissibility_status
hearing_route
created_at
updated_at
closed_at
trace_id
```

### 4.2 核心对象关系

```text
FulfillmentDisputeCase
  ├── DisputeSubmission
  ├── PartyClaim[]
  ├── EvidenceDossier
  │     ├── EvidenceItem[]
  │     ├── CaseTimelineEvent[]
  │     └── ClaimIssueEvidenceMatrix
  ├── Issue[]
  ├── EvidenceRequest[]
  ├── HearingState
  ├── HearingStageRecord[]
  ├── AdjudicationDraft[]
  ├── DeliberationReport[]
  ├── RemedyPlan[]
  ├── ReviewPacket[]
  ├── HumanReviewRecord[]
  ├── ActionRecord[]
  └── EvaluationTrace[]
```

### 4.3 证据上下文快照

订单、物流、支付、售后、聊天、仓储、质检均作为证据快照存在。

```text
OrderEvidenceSnapshot
LogisticsEvidenceSnapshot
PaymentEvidenceSnapshot
AfterSalesEvidenceSnapshot
ChatEvidenceSnapshot
WarehouseEvidenceSnapshot
InspectionEvidenceSnapshot
```

原则：

```text
外部系统实时数据不直接作为审理事实。
进入审理的事实必须形成证据快照并版本化。
Agent 只能引用证据快照和卷宗版本。
ReviewPacket 必须引用冻结证据版本。
```

### 4.4 数据对象不可变原则

以下对象必须版本化，不允许覆盖更新：

```text
EvidenceDossier
AdjudicationDraft
DeliberationReport
ReviewPacket
RemedyPlan
HumanReviewRecord
ActionRecord
```

版本化原则：

```text
修改不是 UPDATE 覆盖，而是创建新 version。
旧版本必须可查询、可审计、可回放。
审核员看到的是冻结版本，不随后台重新计算漂移。
```

---

## 5. 受理与审理三路径

### 5.1 TRANSFERRED：不予受理或转普通售后

适用：

```text
只是查物流
只是催发货
只是问退款进度
没有双方冲突主张
订单信息缺失且无法补全
问题不属于履约争端
```

处理：

```text
不创建完整审理流程。
记录争议接待官判断。
生成转交普通售后说明。
本系统终止。
```

禁止：

```text
不生成 RemedyPlan。
不进入 Platform Human Review。
不触发 Tool Executor。
不把普通请求包装成争端。
```

### 5.2 SIMPLE_HEARING：简易审理

适用：

```text
双方确有争端，但规则明确。
证据充分。
风险较低。
不需要多轮补证。
不需要 AI 评议团。
```

示例：

```text
订单未发货但商家拒绝退款。
退货已被商家签收且超过平台处理时限。
商家在平台聊天中明确同意退款但未执行。
```

流程：

```text
争议接待官
→ 证据书记官
→ 简易审理规则检查
→ Remedy Planner
→ Approval Policy Engine
→ Platform Human Review
→ Tool Executor
```

注意：简易审理仍然必须经过人审和执行门控。

### 5.3 FULL_HEARING：完整争端审理

适用：

```text
签收未收到
退货掉包
破损责任不清
少件错发责任不清
高价值退款或补发
用户/商家主张冲突
证据不完整或相互矛盾
规则适用存在争议
```

流程：

```text
争议接待官
→ 证据书记官
→ AI 主审官 C1-C6
→ Risk Gate
→ 可选 AI 评议团
→ 草案修订
→ Remedy Planner
→ Approval Policy Engine
→ Platform Human Review
→ Tool Executor
```

---

## 6. Agent Runtime Harness 总设计

### 6.1 Harness 总体定位

Agent Runtime Harness 是本系统体现 Agent 工程水平的核心层。

它不是简单的模型调用封装，而是完整的 Agent 运行时治理框架。

```text
Agent Runtime Harness
│
├── Agent Identity & Authority Profile
├── Instruction & Policy Layer
├── Context Assembly Layer
├── Memory Layer
├── Skill Library Layer
├── Tool Gateway Layer
├── Agent Loop Controller
├── Structured Output & Validation Layer
├── Guardrail & Risk Control Layer
├── Human-in-the-loop / Interrupt Layer
├── Lifecycle Hooks / Middleware Layer
├── Observability & Trace Layer
└── Evaluation Feedback Layer
```

### 6.2 Agent Profile 标准字段

每个 Agent 必须有版本化 Profile。

```yaml
agent_id: string
agent_name: string
agent_role: string
agent_version: string
allowed_case_states: []
allowed_workflow_stages: []
allowed_tasks: []
forbidden_tasks: []
allowed_tools: []
forbidden_tools: []
allowed_memory_scopes: []
forbidden_memory_scopes: []
allowed_skills: []
output_schema: string
max_iterations: integer
max_model_calls: integer
max_tool_calls: integer
max_runtime_seconds: integer
max_input_tokens: integer
max_output_tokens: integer
requires_human_approval_for: []
risk_escalation_rules: []
```

Profile 原则：

```text
未显式允许即拒绝。
Profile 不能扩大调用方权限。
Profile 必须被 Trace 记录。
Profile 修改必须版本化。
```

### 6.3 Instruction 分层

不允许所有指令写成一个巨型 Prompt。

```text
Global System Policy
  ↓
Product Boundary Policy
  ↓
Agent Role Instruction
  ↓
Workflow Stage Instruction
  ↓
Skill Instruction
  ↓
Runtime Constraint
```

示例：

```text
Global System Policy:
你是平台履约争端审理系统中的受控 Agent，不得执行影响用户权益或商家成本的动作。

Product Boundary:
本系统只处理用户或商家主动发起的履约争端，不处理普通订单查询、物流监控或泛售后。

Agent Role:
你是 AI 主审官，只负责生成审理分析和草案，不是最终裁决者。

Stage:
当前阶段是 Evidence Cross-check Stage，请只分析证据支持关系、冲突关系和缺口。

Skill:
当前使用 SignedNotReceivedSkill，请关注签收凭证、签收人、代收点、快递沟通记录。

Runtime:
必须输出 JSON；不得承诺退款；不得输出最终裁决；每个事实判断必须引用证据。
```

### 6.4 Context Assembly Layer

Context Builder 必须根据运行时动态组装上下文。

输入：

```text
agent_profile
case_id
case_version
workflow_stage
hearing_stage
dossier_version
skill_id
token_budget
actor_role
access_scope
```

上下文分层：

```text
必带上下文：
- case_id
- dispute_type
- current_case_status
- current_workflow_stage
- current_risk_level
- actor_role
- allowed_actions
- forbidden_actions

阶段上下文：
- current_hearing_stage
- previous_stage_outputs
- current_stage_input
- stage_goal
- output_schema

证据上下文：
- EvidenceDossier 摘要
- PartyClaims
- CaseTimeline
- EvidenceItems
- MissingEvidence
- ClaimIssueEvidenceMatrix

规则上下文：
- policy_candidates
- rule_version
- applicable_category_rules
- SLA rules

可检索上下文：
- 原始聊天记录
- 原始物流轨迹
- 证据文件解析全文
- 平台规则全文
- 历史相似案件

禁止直接注入：
- 未脱敏手机号、地址、证件号
- 与当前案件无关的历史订单
- 无关聊天全文
- 未授权商家或用户隐私
- 大段无关附件内容
```

Context 片段必须携带来源：

```json
{
  "source_type": "evidence_item",
  "source_id": "EV-001",
  "version": 3,
  "captured_at": "2026-07-02T10:00:00Z",
  "redaction_level": "role_based",
  "content_summary": "物流显示 2026-07-01 18:35 已签收"
}
```

### 6.5 Memory Layer

Memory 不是事实源。

```text
Workflow State + PostgreSQL = 事实源
Agent Memory = 上下文增强
```

Memory 分层：

| Memory 类型 | 生命周期 | 用途 | 是否事实源 |
|---|---|---|---|
| Run Memory | 单次 Agent Run | 工具结果、临时观察 | 否 |
| Case Memory | 单个案件 | 案件摘要、争点摘要、补证历史 | 否 |
| Hearing Memory | 审理流程内 | C1-C6 阶段输出摘要 | 否 |
| Domain Memory | 跨案件 | 场景模板、证据要求、规则摘要 | 否 |
| Evaluation Memory | 离线 | 失败模式、审核修改、人机差异 | 否 |

禁止：

```text
禁止将单案人工审核意见直接写入全局记忆。
禁止用 Memory 中的历史判断替代当前案件证据。
禁止用用户历史争议推定当前用户责任。
```

### 6.6 Skill Library Layer

场景差异由 Skill 承载，不靠新建 Agent。

Skill 目录建议：

```text
/skills/signed_not_received.md
/skills/return_swap_dispute.md
/skills/damaged_goods_dispute.md
/skills/missing_or_wrong_item.md
/skills/merchant_refund_rejection.md
/skills/evidence_request_generation.md
/skills/rule_application.md
/skills/review_packet_generation.md
/skills/fairness_consistency_check.md
```

Skill 模板：

```text
skill_id:
skill_name:
version:
applicable_dispute_types:
applicable_agents:
required_context:
required_evidence:
reasoning_steps:
risk_flags:
forbidden_behavior:
output_schema:
example_cases:
evaluation_criteria:
```

### 6.7 Tool Gateway Layer

Agent 不直接调用工具，必须经过 Tool Gateway。

工具分级：

| 类型 | 示例 | Agent 权限 |
|---|---|---|
| Read Tools | query_order、query_logistics、query_policy | 可按 Profile 调用 |
| Draft Tools | draft_evidence_request、draft_review_packet | 可按 Profile 调用 |
| Write Tools | create_evidence_request、save_hearing_record | 需 Workflow 授权 |
| Execution Tools | create_refund、create_replacement、close_after_sales | Agent 永远不可直接调用 |

Tool 调用统一请求：

```json
{
  "tool_name": "logistics_facts.read",
  "tool_version": "1.0",
  "case_id": "CASE_xxx",
  "agent_run_id": "RUN_xxx",
  "arguments": {},
  "reason": "用于核验签收争点",
  "requested_fields": [],
  "idempotency_key": "tool-case-run-hash"
}
```

Tool 返回：

```json
{
  "status": "SUCCESS",
  "data": {},
  "source_refs": [],
  "redactions": [],
  "audit_id": "AUD_xxx"
}
```

Tool Gateway 必须校验：

```text
工具是否注册
Agent 是否有权限
案件状态是否允许
参数是否符合 Schema
是否需要人审
是否命中幂等
是否存在越权数据访问
结果是否需要脱敏
```

### 6.8 Agent Loop Controller

统一循环：

```text
1. load_agent_profile
2. validate_case_state
3. read_memory
4. build_context
5. load_skill
6. call_model
7. parse_structured_output
8. validate_schema
9. check_guardrails
10. call_allowed_tool_if_needed
11. observe_tool_result
12. update_run_memory
13. check_stop_condition
14. return_result_to_workflow
```

停止条件：

```text
目标完成
无更多工具调用
达到最大迭代次数
达到最大工具调用次数
达到最大模型调用次数
达到最大 token budget
达到最大运行时长
重复调用同一工具且无新增信息
Schema 校验多次失败
命中高风险输出
需要人工确认
置信度低于阈值
```

### 6.9 Structured Output & Validation

Agent 输出必须使用统一 Envelope：

```json
{
  "agent_name": "AI_Presiding_Judge_Agent",
  "agent_run_id": "AR-10086",
  "case_id": "DC-10086",
  "stage": "evidence_cross_check",
  "status": "success",
  "confidence": 0.82,
  "result": {},
  "risk_flags": [],
  "missing_information": [],
  "recommended_next_action": {},
  "requires_human_review": false,
  "trace_id": "trace-xxx"
}
```

校验分三层：

```text
Schema 校验：字段、类型、枚举、必填。
引用校验：证据、规则、版本必须存在。
业务校验：状态、权限、禁止行为、金额、动作一致性。
```

### 6.10 Guardrail & Risk Control

Guardrail 类型：

```text
Input Guardrail:
检测提示注入、越权请求、无关请求、敏感信息。

Context Guardrail:
防止把商家隐私暴露给用户，把用户隐私暴露给商家。

Tool Guardrail:
检查工具权限、参数、审批状态、幂等 key。

Output Guardrail:
检查是否承诺退款、是否输出最终裁决、是否站队、是否泄露隐私。

Risk Guardrail:
命中高价值退款、退货掉包、签收未收到、疑似欺诈、法律风险时强制进入人审或评议团。
```

### 6.11 Human Interrupt

HITL 中断类型：

```text
need_user_evidence
need_merchant_evidence
need_platform_review
need_risk_review
approval_required
draft_revision_required
manual_takeover_required
```

中断 Payload：

```json
{
  "interrupt_type": "approval_required",
  "case_id": "DC-10086",
  "review_packet_id": "RP-10086",
  "action_type": "partial_refund",
  "amount": 34950,
  "reason_summary": "当前证据支持部分退款，但仍存在签收证明缺口",
  "evidence_summary": "物流显示签收，用户提供未收到说明，缺少签收照片",
  "risk_flags": ["high_value", "evidence_conflict"],
  "allowed_decisions": ["approve", "reject", "edit", "request_more_evidence", "escalate"]
}
```

### 6.12 Lifecycle Hooks

必须设计以下 Hook：

| Hook | 作用 |
|---|---|
| before_agent_run | 初始化 run_id、权限、预算、trace |
| before_context_build | 决定上下文和脱敏策略 |
| after_context_build | 记录上下文来源和 token |
| before_llm_call | 注入指令、Skill、工具清单 |
| after_llm_call | 记录模型输出、token、延迟 |
| before_tool_call | 权限、参数、幂等、审批检查 |
| after_tool_call | 记录工具结果、错误、重试 |
| before_memory_read | 判断读取哪些记忆 |
| after_memory_write | 写入案件摘要或评估信号 |
| before_human_interrupt | 构造审批卡 |
| after_human_resume | 写入人工决策并恢复 Workflow |
| on_guardrail_violation | 降级、转人工、拒绝执行 |
| on_agent_error | 重试、失败诊断、安全中断 |
| after_agent_run | 输出结果、trace、评估样本 |

### 6.13 Observability

每次 Agent Run 记录：

```text
workflow_trace_id
agent_run_id
case_id
stage
model_name
model_params
prompt_version
skill_version
agent_profile_version
context_sources
tool_calls
tool_args_hash
tool_results_summary
guardrail_events
human_interrupts
human_decisions
schema_validation_result
token_usage
latency
cost
confidence
final_status
```

---

## 7. 每个 Agent 的详细 Harness 设定

## 7.1 Dispute Intake Officer Agent：争议接待官

### 7.1.1 定位

争议接待官负责判断用户或商家的输入是否构成履约争端，并生成案件受理草案。

它是入口 Agent，不是客服 Agent，也不是审理 Agent。

### 7.1.2 触发时机

```text
用户或商家提交争端描述时触发。
案件重新提交或补充基础信息后可再次触发。
```

### 7.1.3 输入

```json
{
  "submission_id": "SUB-xxx",
  "initiator_role": "user|merchant",
  "raw_text": "物流显示签收，但我没收到货",
  "order_reference": "O-xxx",
  "after_sales_reference": "AS-xxx",
  "logistics_reference": "L-xxx",
  "attachments": [],
  "channel": "web|app|merchant_console|im"
}
```

### 7.1.4 输出 Schema

```json
{
  "is_potential_dispute": true,
  "admissibility_recommendation": "ACCEPTED|NEED_MORE_INFO|TRANSFERRED",
  "dispute_type": "SIGNED_NOT_RECEIVED|RETURN_SWAP|DAMAGED_GOODS|MISSING_ITEM|REFUND_REJECTION|OTHER",
  "initiator": "USER|MERCHANT",
  "claims": [
    {
      "party": "USER",
      "claim_text": "物流显示签收但本人未收到",
      "source_ref": "SUB-xxx"
    }
  ],
  "requested_remedy": "REFUND|REPLACEMENT|RETURN|REJECT_REFUND|OTHER|UNKNOWN",
  "missing_initial_fields": [],
  "risk_signals": [],
  "confidence": 0.86,
  "next_step": "BUILD_DOSSIER"
}
```

### 7.1.5 Context 策略

必带：

```text
提交原文
提交人身份
渠道
订单号/售后单号/物流号
附件元数据
```

按需查询：

```text
订单是否存在
售后单是否存在
物流单是否关联订单
用户/商家是否属于该订单
```

不注入：

```text
无关历史订单
无关历史争端
商家内部风控标签明文
```

### 7.1.6 Memory 策略

读取：

```text
Run Memory
当前提交草稿
```

不读取：

```text
Evaluation Memory
历史相似案件判断结果
```

写入：

```text
受理摘要
缺失字段列表
初步争端类型
```

### 7.1.7 Skill

可加载：

```text
DisputeAdmissibilitySkill
DisputeTypeClassificationSkill
InitialClaimExtractionSkill
```

### 7.1.8 工具权限

允许：

```text
order_reference.read
after_sales_reference.read
logistics_reference.read
submission_attachment.metadata_read
```

禁止：

```text
evidence_dossier.write
refund.execute
replacement.execute
case.close
review.approve
```

### 7.1.9 Loop 设置

```text
max_iterations: 3
max_tool_calls: 5
max_model_calls: 2
max_runtime_seconds: 20
```

### 7.1.10 Guardrail

必须检查：

```text
是否把普通查询误判为争端
是否臆造订单事实
是否承诺退款/补发
是否直接判断商家或用户责任
是否泄露任一方隐私
```

### 7.1.11 失败策略

| 失败 | 策略 |
|---|---|
| 无法识别订单 | 返回 NEED_MORE_INFO |
| 不构成争端 | 返回 TRANSFERRED |
| 输出 Schema 错误 | 最多修复 1 次，失败转人工接待 |
| 工具查询失败 | 标记不确定，不臆造事实 |

---

## 7.2 Evidence Clerk Agent：证据书记官

### 7.2.1 定位

证据书记官负责构建证据卷宗，是 AI 证据工作室的核心 Agent。

它只整理证据，不判断责任，不生成执行方案。

### 7.2.2 触发时机

```text
争端案件创建后触发。
用户/商家补充证据后触发卷宗更新。
平台审核员要求补证后触发卷宗重建。
```

### 7.2.3 输入

```json
{
  "case_id": "DC-xxx",
  "case_version": 1,
  "submission_version": 1,
  "uploaded_evidence_refs": [],
  "allowed_data_sources": ["order", "logistics", "payment", "after_sales", "chat", "warehouse", "inspection"],
  "current_dossier_version": null
}
```

### 7.2.4 输出 Schema

```json
{
  "dossier_version": 1,
  "timeline": [],
  "party_claims": [],
  "evidence_catalog": [],
  "claim_issue_evidence_matrix": [],
  "conflicts": [],
  "gaps": [],
  "duplicate_groups": [],
  "parser_warnings": [],
  "policy_candidates": [],
  "source_citations": []
}
```

### 7.2.5 Context 策略

必带：

```text
case_id
submission
party_claims
已有 EvidenceItem 元数据
当前 dossier_version
```

按需查询：

```text
订单快照
物流快照
支付快照
售后记录
聊天记录
仓储发货记录
退货验收记录
质检记录
附件解析结果
```

上下文压缩：

```text
聊天记录按时间线摘要。
物流轨迹保留关键节点。
图片/PDF 保留 OCR 摘要与原件引用。
大量证据只保留 catalog + relevant snippets。
```

### 7.2.6 Memory 策略

读取：

```text
Case Memory
当前卷宗摘要
历史补证请求
```

写入：

```text
卷宗版本摘要
新增证据摘要
证据缺口摘要
冲突摘要
```

禁止：

```text
禁止把证据书记官的推断写成事实源。
禁止覆盖旧卷宗版本。
```

### 7.2.7 Skill

可加载：

```text
EvidenceDossierBuildSkill
TimelineConstructionSkill
EvidenceReliabilityTaggingSkill
ClaimIssueEvidenceMatrixSkill
SignedNotReceivedEvidenceSkill
ReturnSwapEvidenceSkill
DamagedGoodsEvidenceSkill
```

### 7.2.8 工具权限

允许：

```text
order_facts.read
logistics_facts.read
payment_facts.read
after_sales_facts.read
chat_record.read
warehouse_record.read
inspection_record.read
evidence_parser.read
evidence_metadata.write_draft
```

需 Workflow 授权：

```text
evidence_dossier.create_version
evidence_item.append
```

禁止：

```text
liability.decide
remedy.plan
refund.execute
replacement.execute
case.close
```

### 7.2.9 Loop 设置

```text
max_iterations: 8
max_tool_calls: 15
max_model_calls: 4
max_runtime_seconds: 90
```

### 7.2.10 Guardrail

必须检查：

```text
是否把当事人陈述当成平台事实
是否隐藏不利证据
是否无引用生成事实
是否输出责任判断
是否生成退款/补发建议
是否泄露隐私
```

### 7.2.11 失败策略

| 失败 | 策略 |
|---|---|
| 某证据解析失败 | 保留原件，标记 parser_warning |
| 外部工具部分失败 | 生成不完整卷宗并标记 gaps |
| 证据冲突无法解释 | 标记 conflict，不裁决 |
| Schema 失败 | 修复 2 次，失败转人工卷宗处理 |

---

## 7.3 AI Presiding Judge Agent：AI 主审官

### 7.3.1 定位

AI 主审官是完整争端审理的核心认知 Agent。

它不是最终法官，只能生成结构化审理结果和裁决草案。

### 7.3.2 触发时机

```text
案件被 Router 判定为 FULL_HEARING。
补证完成后继续审理。
评议团提出重大异议后进行草案修订。
```

### 7.3.3 输入

```json
{
  "case_id": "DC-xxx",
  "hearing_id": "H-xxx",
  "stage": "C1_ISSUE_FRAMING",
  "dossier_version": 3,
  "previous_stage_outputs": {},
  "skill_id": "signed_not_received",
  "risk_level": "HIGH"
}
```

### 7.3.4 Stage 输出

C1：IssueFramingResult

```json
{
  "issues": [
    {
      "issue_id": "ISS-1",
      "issue_question": "用户是否实际收到商品？",
      "issue_type": "SIGNED_RECEIPT_FACT",
      "required_evidence": ["签收证明", "用户快递沟通记录"],
      "priority": "HIGH",
      "status": "PENDING"
    }
  ]
}
```

C2：EvidenceGapResult

```json
{
  "gaps": [
    {
      "issue_id": "ISS-1",
      "missing_evidence": "签收照片",
      "target_party": "MERCHANT_OR_PLATFORM",
      "importance": "CRITICAL",
      "reason": "当前物流仅显示签收状态，缺少签收凭证支持实际交付"
    }
  ]
}
```

C3：EvidenceRequestDraftResult

```json
{
  "requests": [
    {
      "target_party": "USER",
      "materials": ["与快递沟通截图", "未收到情况说明"],
      "neutral_message": "为核实物流显示签收但您反馈未收到的情况，请补充以下材料。",
      "deadline_hours": 24
    }
  ]
}
```

C4：EvidenceCrossCheckResult

```json
{
  "findings": [],
  "conflicts": [],
  "manual_check_points": [],
  "confidence": 0.78
}
```

C5：RuleApplicationResult

```json
{
  "matched_rules": [],
  "satisfied_conditions": [],
  "unsatisfied_conditions": [],
  "pending_conditions": [],
  "rule_version": "R-2026-01"
}
```

C6：AdjudicationDraftResult

```json
{
  "non_final": true,
  "fact_findings": [],
  "evidence_assessment": [],
  "policy_application": [],
  "recommended_decision": "REQUEST_MORE_EVIDENCE",
  "confidence": 0.72,
  "reviewer_attention": [],
  "risk_flags": []
}
```

### 7.3.5 Context 策略

必带：

```text
EvidenceDossier 当前冻结版本
PartyClaims
IssueList 当前版本
上一阶段输出
当前 Skill 指令
当前规则候选
```

按需查询：

```text
相似案件模式
平台规则全文
类目规则
SLA 规则
历史补证结果
```

禁止：

```text
未授权历史用户风险明文
与当前争点无关的全部聊天
未脱敏隐私
```

### 7.3.6 Memory 策略

读取：

```text
Hearing Memory
Case Memory
Domain Memory
```

写入：

```text
每个 Stage 输出摘要
关键争点变化
证据缺口变化
草案版本摘要
```

禁止：

```text
禁止把低置信度推断写成事实。
禁止用历史案件结果替代当前证据。
```

### 7.3.7 Skill

可加载：

```text
IssueFramingSkill
EvidenceGapSkill
EvidenceRequestGenerationSkill
EvidenceCrossCheckSkill
RuleApplicationSkill
DraftGenerationSkill
SignedNotReceivedSkill
ReturnSwapDisputeSkill
DamagedGoodsDisputeSkill
MissingOrWrongItemSkill
```

### 7.3.8 工具权限

允许：

```text
evidence_dossier.read
hearing_state.read
policy.search
similar_case.search
rule_version.read
evidence_requirement_template.read
```

需 Workflow 授权：

```text
hearing_stage_record.write
evidence_request.create_draft
adjudication_draft.save
```

禁止：

```text
refund.execute
replacement.execute
after_sales.reject
after_sales.close
approval.approve
```

### 7.3.9 Loop 设置

不同 Stage 使用不同预算：

| Stage | max_iterations | max_tool_calls | max_model_calls |
|---|---:|---:|---:|
| C1 Issue Framing | 3 | 6 | 2 |
| C2 Evidence Gap | 3 | 6 | 2 |
| C3 Evidence Request | 2 | 4 | 2 |
| C4 Evidence Cross-check | 5 | 10 | 3 |
| C5 Rule Application | 4 | 8 | 3 |
| C6 Draft Generation | 2 | 4 | 2 |
| Draft Revision | 3 | 6 | 2 |

### 7.3.10 Guardrail

必须检查：

```text
是否输出最终裁决
是否承诺退款/补发
是否创造不存在的规则
是否无证据引用作事实认定
是否过度推断欺诈
是否偏向用户或商家
是否绕过人审
```

### 7.3.11 失败策略

| 失败 | 策略 |
|---|---|
| Stage 输出无效 | 修复 2 次，失败中断到人工 |
| 规则检索失败 | 标记规则不可判定，不生成强结论 |
| 证据缺口严重 | 输出 REQUEST_MORE_EVIDENCE |
| 置信度低 | 触发评议团或人工重点审核 |

---

## 7.4 AI Deliberation Panel：AI 评议团

### 7.4.1 定位

AI 评议团是高风险争端的按需多 Agent 质询机制。

它不是最终裁决者，不直接改主审官结论，只生成 DeliberationReport。

### 7.4.2 触发条件

任一命中即可触发：

```text
refund_amount > threshold
replacement_value > threshold
dispute_type in [RETURN_SWAP, SIGNED_NOT_RECEIVED]
adjudication_confidence < 0.75
evidence_conflict_count >= 2
rule_application_has_pending_conditions = true
risk_flags contains FRAUD_SUSPECTED
reviewer_requested_panel = true
```

### 7.4.3 输入冻结原则

评议团所有 Critic 必须使用同一份冻结输入。

```text
case_snapshot_version
dossier_version
adjudication_draft_version
rule_version
remedy_plan_candidate_version
```

禁止某个 Critic 读取更新后的上下文，避免评议结果不可比。

### 7.4.4 Critic Agent 列表

```text
Evidence Critic
Rule Critic
Risk Critic
Remedy Critic
Fairness Critic
```

### 7.4.5 Evidence Critic Harness

目标：只审查证据链。

关注问题：

```text
证据是否足以支持草案事实认定
是否遗漏关键证据
是否存在未解释冲突
是否过度解释某份证据
哪些证据需要人工重点查看
```

允许工具：

```text
evidence_dossier.read
hearing_record.read
```

禁止：

```text
rule.modify
remedy.plan
approval.approve
action.execute
```

输出：

```json
{
  "critic": "EVIDENCE_CRITIC",
  "severity": "HIGH",
  "findings": [],
  "blocking_issues": [],
  "recommended_revision": "REQUEST_MORE_EVIDENCE"
}
```

### 7.4.6 Rule Critic Harness

目标：只审查规则适用。

关注问题：

```text
规则版本是否正确
是否遗漏类目规则、商家规则、SLA
规则条件是否真正满足
是否把常识当成规则
```

允许工具：

```text
policy.search
rule_version.read
category_rule.read
sla_rule.read
```

输出：

```json
{
  "critic": "RULE_CRITIC",
  "severity": "MEDIUM",
  "rule_issues": [],
  "missing_rules": [],
  "recommended_revision": "ADD_PENDING_CONDITION"
}
```

### 7.4.7 Risk Critic Harness

目标：只审查风险。

关注问题：

```text
高金额风险
疑似恶意退款
频繁争议
舆情/法务/风控风险
是否必须升级人工
```

允许工具：

```text
case_risk.read
historical_risk_summary.read
```

注意：历史风险只能作为风险提示，不能作为责任判断依据。

### 7.4.8 Remedy Critic Harness

目标：只审查执行方案一致性。

关注问题：

```text
执行动作是否与事实和规则一致
金额是否超过上限
补发是否需要库存确认
继续补证是否比直接退款更稳
动作是否不可逆
```

允许工具：

```text
remedy_plan.read
amount_constraint.read
inventory_availability.read
```

禁止执行任何动作。

### 7.4.9 Fairness Critic Harness

目标：审查公平性和一致性。

关注问题：

```text
是否偏向用户或商家
举证责任是否分配合理
同类历史案件是否一致
对双方表达是否中立
是否存在同案不同判风险
```

允许工具：

```text
similar_case.search
fairness_policy.read
```

禁止：

```text
用历史结果替代当前证据
输出最终裁决
```

### 7.4.10 Panel Aggregator

聚合规则：

```text
重大异议不得被平均分抵消。
任一 Critic 输出 severity=BLOCKER，Panel 结果必须标记 revision_required。
多个 Critic 意见冲突时，保留冲突并交给审核员。
Critic 超时不能视为无异议。
```

输出：

```json
{
  "deliberation_id": "DL-xxx",
  "panel_result": "NO_MAJOR_OBJECTION|REVISION_REQUIRED|MANUAL_REVIEW_REQUIRED",
  "major_risks": [],
  "critic_reports": [],
  "consensus": [],
  "disagreements": [],
  "recommended_revision": "",
  "reviewer_attention": []
}
```

---

## 7.5 Review Copilot Agent：审核辅助官

### 7.5.1 定位

审核辅助官只服务平台审核员，帮助理解案件和 AI 输出。

它不拥有审批权，不触发执行。

### 7.5.2 输入

```json
{
  "review_id": "RV-xxx",
  "case_id": "DC-xxx",
  "review_packet_version": 2,
  "question": "为什么建议继续补证而不是直接退款？"
}
```

### 7.5.3 输出

```json
{
  "answer": "当前建议继续补证主要因为签收证明缺失...",
  "fact_refs": [],
  "rule_refs": [],
  "draft_refs": [],
  "uncertainties": [],
  "suggested_review_focus": []
}
```

### 7.5.4 Context 策略

可读：

```text
ReviewPacket
EvidenceDossier
AdjudicationDraft
DeliberationReport
RemedyPlan
HumanReviewHistory 当前案件内记录
```

不可读：

```text
未授权历史案件明细
平台内部模型 Prompt
无关商家/用户数据
```

### 7.5.5 工具权限

允许：

```text
review_packet.read
evidence_dossier.read
deliberation_report.read
similar_case.search_summary
```

禁止：

```text
review.approve
review.reject
remedy.modify
action.execute
```

### 7.5.6 Guardrail

必须检查：

```text
是否替审核员做决定
是否生成“你应该批准”这类强命令
是否泄露内部不可见信息
是否把 AI 草案说成最终结论
```

---

## 7.6 Evaluation Agent：离线复盘官

### 7.6.1 定位

Evaluation Agent 不参与实时案件审理。

它只在案件关闭后，对脱敏快照、Trace、人审修改、执行结果进行离线分析。

### 7.6.2 输入

```json
{
  "case_id": "DC-xxx",
  "closed_case_snapshot": {},
  "agent_runs": [],
  "human_review_records": [],
  "action_records": [],
  "user_feedback": null,
  "merchant_feedback": null
}
```

### 7.6.3 输出

```json
{
  "evaluation_id": "EVL-xxx",
  "case_quality_score": 0.82,
  "agent_error_types": [],
  "human_modification_reason": [],
  "deliberation_effectiveness": {},
  "prompt_improvement_suggestions": [],
  "skill_improvement_suggestions": [],
  "rule_gap_suggestions": [],
  "test_case_candidate": true
}
```

### 7.6.4 评估指标

```text
受理准确率
证据缺口识别准确率
争点覆盖率
规则引用准确率
主审草案通过率
审核员修改率
评议团有效拦截率
Fairness Critic 一致性提升率
补证请求有效率
执行成功率
高风险误判率
```

### 7.6.5 禁止

```text
不参与当前案件处理
不自动修改 Prompt
不自动发布 Skill
不自动调整规则
不把单案结论写入全局偏好
```

---

## 8. Workflow 工程设计

### 8.1 FulfillmentDisputeWorkflow

主 Workflow：

```text
S1 Dispute Submission
S2 Intake & Admissibility
S3 Evidence Dossier Building
S4 Hearing Route Decision
S5 Simple Hearing or Full Hearing
S6 Risk Gate
S7 Deliberation Panel if needed
S8 Draft Revision
S9 Remedy Planning
S10 Approval Policy
S11 Platform Human Review
S12 Tool Execution
S13 Case Closure
S14 Evaluation Trace Emit
```

状态：

```text
RECEIVED
INTAKE_ANALYZING
DOSSIER_BUILDING
ADMISSIBILITY_ROUTING
TRANSFERRED
SIMPLE_HEARING
FULL_HEARING
WAITING_EVIDENCE
DELIBERATING
REMEDY_PLANNING
WAITING_HUMAN_REVIEW
RETURNED_FOR_EVIDENCE
APPROVED
REJECTED
EXECUTING
EXECUTION_FAILED
CLOSED
CANCELLED
MANUAL_TAKEOVER
```

约束：

```text
TRANSFERRED 是本系统终态。
APPROVED 只能由 HumanReviewRecord 触发。
EXECUTING 只能从 APPROVED 进入。
CLOSED 必须存在执行结果或明确无动作关闭理由。
Agent API 不拥有直接变更状态权限。
```

### 8.2 DisputeHearingWorkflow

```text
FRAMING_ISSUES
CHECKING_GAPS
REQUESTING_EVIDENCE
WAITING_EVIDENCE
CROSS_CHECKING
APPLYING_RULES
DRAFTING
RISK_GATE
DELIBERATING
REVISING_DRAFT
HEARING_COMPLETED
HEARING_INTERRUPTED
```

补证循环规则：

```text
max_evidence_rounds 默认 2。
每轮补证设置 deadline。
超时后根据现有证据继续或转人工。
补证后必须由证据书记官更新 EvidenceDossier 版本。
AI 主审官只能基于最新冻结版本继续审理。
```

### 8.3 DeliberationPanelWorkflow

步骤：

```text
freeze_input_snapshot
select_critics
run_critics_in_parallel
collect_critic_reports
aggregate_deliberation_report
return_to_presiding_judge_or_human_review
```

规则：

```text
单个 Critic 失败不等于无异议。
Critic 超时必须在报告中标记。
重大异议必须保留。
聚合器不能删除少数高严重度意见。
```

### 8.4 HumanReviewWorkflow

等待 Signal：

```text
review_record_id
reviewer_id
decision
packet_version
approved_action_hash
comment
```

拒绝条件：

```text
packet_version 过期
action_hash 不匹配
审核员无权限
案件状态不允许审批
审批动作超出 ReviewPacket 范围
```

### 8.5 ExecutionWorkflow

执行约束：

```text
必须引用 HumanReviewRecord。
必须校验 action_hash。
必须使用幂等键。
外部结果未知时先查询，不盲目重试。
不可逆动作不得自动补偿。
所有执行结果写入 ActionRecord。
```

---

## 9. Remedy Planner、Approval Policy、Tool Executor

### 9.1 Remedy Planner

Planner 只做动作映射，不改事实，不重新断案。

输入：

```text
source_type
source_version
adjudication_draft
simple_hearing_result
deliberation_report
amount_constraints
business_constraints
```

输出：

```json
{
  "plan_id": "RP-xxx",
  "candidate_actions": [
    {
      "action_type": "REQUEST_MORE_EVIDENCE",
      "target": "USER",
      "deadline_hours": 24
    }
  ],
  "notification_plan": {},
  "approval_required": true,
  "risk_level": "HIGH"
}
```

禁止：

```text
推翻 AI 主审官事实认定
创造新的责任判断
直接执行动作
绕过审批
```

### 9.2 Approval Policy Engine

所有正式版动作默认 `autoApprove=false`。

输入：

```text
case_risk_level
remedy_action_type
amount
confidence
deliberation_result
critic_severity
historical_risk_summary
```

输出：

```json
{
  "approval_required": true,
  "review_roles": ["PLATFORM_REVIEWER"],
  "extra_roles": ["RISK_REVIEWER"],
  "reason": "高价值签收未收到争议，且证据存在冲突",
  "review_packet_required": true
}
```

### 9.3 Tool Executor

Tool Executor 执行强类型动作。

动作示例：

```text
CREATE_REFUND
CREATE_REPLACEMENT
REJECT_AFTER_SALES
CLOSE_AFTER_SALES
REQUEST_MORE_EVIDENCE
NOTIFY_USER
NOTIFY_MERCHANT
CREATE_PLATFORM_TICKET
```

执行前校验：

```text
HumanReviewRecord 存在
action_hash 匹配
动作参数与审核快照一致
操作者有权限
幂等键未重复执行
案件状态允许执行
```

禁止：

```text
从模型自由文本提取动作直接执行
接收未经审批动作
接收 C 层直接触发
跳过审计
修改 AI 草案或人工审核意见
```

---

## 10. API 文档目标态

本节是正式版 API 目标态，不要求本次改代码。

### 10.1 API 命名原则

正式版不使用 `/v2`、`/v3` 等版本路径。

统一使用：

```text
/api/disputes
/api/reviews
/internal/agents
/internal/evidence
```

版本治理通过：

```text
Header
Schema version
Prompt version
Skill version
Agent Profile version
Ruleset version
```

实现，不体现在 URL 中。

### 10.2 通用约定

```text
写请求必须支持 Idempotency-Key。
所有响应包含 requestId 和 traceId。
时间使用 UTC ISO-8601。
金额使用最小货币单位。
分页使用稳定游标。
错误不暴露 Prompt、密钥、堆栈或内部工具参数。
```

### 10.3 外部争端 API

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/disputes` | 发起争端 |
| GET | `/api/disputes/{caseId}` | 查询案件 |
| GET | `/api/disputes` | 查询案件列表 |
| POST | `/api/disputes/{caseId}/evidence` | 提交证据 |
| GET | `/api/disputes/{caseId}/dossiers/{version}` | 查询卷宗 |
| GET | `/api/disputes/{caseId}/hearing` | 查询审理状态 |
| POST | `/api/disputes/{caseId}/cancel` | 取消争端 |

#### POST /api/disputes 请求示例

```json
{
  "initiatorRole": "USER",
  "orderReference": "O-10086",
  "afterSalesReference": "AS-10086",
  "logisticsReference": "L-10086",
  "description": "物流显示签收，但我本人没有收到货。",
  "attachmentIds": []
}
```

响应示例：

```json
{
  "requestId": "REQ-xxx",
  "traceId": "TRACE-xxx",
  "caseId": "DC-10086",
  "caseStatus": "INTAKE_ANALYZING",
  "message": "争端已提交，系统正在进行受理分析。"
}
```

### 10.4 人审 API

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/reviews/tasks` | 审核任务列表 |
| GET | `/api/reviews/{reviewId}/packet` | 获取审核包 |
| POST | `/api/reviews/{reviewId}/approve` | 批准 |
| POST | `/api/reviews/{reviewId}/modify-and-approve` | 修改后批准 |
| POST | `/api/reviews/{reviewId}/return` | 退回补证 |
| POST | `/api/reviews/{reviewId}/reject` | 拒绝 |
| POST | `/api/reviews/{reviewId}/escalate` | 升级 |
| POST | `/api/reviews/{reviewId}/copilot/query` | 审核辅助问答 |

审核动作请求必须携带：

```json
{
  "packetVersion": 3,
  "approvedActionHash": "hash-xxx",
  "decisionReason": "证据仍不足，要求用户补充快递沟通截图。"
}
```

### 10.5 内部 Agent API

| 方法 | 路径 | 输出 |
|---|---|---|
| POST | `/internal/agents/intake/analyze` | DisputeIntakeResult |
| POST | `/internal/agents/evidence/build` | EvidenceDossierResult |
| POST | `/internal/agents/hearing/run-stage` | C1-C6 Stage Result |
| POST | `/internal/agents/deliberation/run` | DeliberationReport |
| POST | `/internal/agents/review-copilot/query` | ReviewCopilotAnswer |
| POST | `/internal/agents/evaluation/analyze` | EvaluationResult |

内部 API 只允许服务身份访问，并校验：

```text
caseId
workflowStage
agentProfile
callerService
traceId
```

### 10.6 Evidence Parser API

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/internal/evidence/parse` | 提交证据解析任务 |
| GET | `/internal/evidence/tasks/{taskId}` | 查询解析任务 |

解析 API 返回派生内容和警告，不返回业务责任结论。

---

## 11. 数据模型目标态

### 11.1 表清单

```text
fulfillment_dispute_case
dispute_submission
party_claim
evidence_item
evidence_dossier
evidence_dossier_item
case_timeline_event
issue
claim_issue_evidence_link
evidence_request
hearing_state
hearing_stage_record
adjudication_draft
deliberation_report
deliberation_finding
remedy_plan
remedy_action
approval_policy_decision
review_packet
human_review_record
action_record
agent_run
agent_tool_call
agent_guardrail_event
agent_memory_entry
skill_version
prompt_version
evaluation_record
audit_log
```

### 11.2 核心表字段建议

#### fulfillment_dispute_case

```text
id
case_no
initiator_role
user_id
merchant_id
order_id
after_sales_id
logistics_id
dispute_type
case_status
risk_level
current_stage
hearing_route
created_at
updated_at
closed_at
trace_id
```

#### evidence_dossier

```text
id
case_id
version
summary
timeline_version
matrix_version
created_by_agent_run_id
created_at
trace_id
```

#### adjudication_draft

```text
id
case_id
version
hearing_id
non_final
fact_findings
evidence_assessment
policy_application
recommended_decision
confidence
reviewer_attention
created_by_agent_run_id
created_at
trace_id
```

#### deliberation_report

```text
id
case_id
version
draft_id
panel_result
major_risks
consensus
disagreements
recommended_revision
created_at
trace_id
```

#### review_packet

```text
id
case_id
version
case_version
dossier_version
adjudication_draft_version
deliberation_report_version
remedy_plan_version
ruleset_version
prompt_version
skill_version
packet_status
created_at
trace_id
```

### 11.3 ReviewPacket 冻结输入

ReviewPacket 必须引用冻结版本：

```text
case_version
dossier_version
issue_version
adjudication_draft_version
deliberation_report_version
remedy_plan_version
ruleset_version
prompt_version
skill_version
```

---

## 12. 前端交互设计

### 12.1 页面收敛原则

不做订单中心，不做物流大盘，只做争端审理。

页面：

```text
/disputes/new                 争端发起入口
/disputes                     争端案件列表
/disputes/:caseId             争端案件工作台
/disputes/:caseId/evidence    AI 证据工作室
/disputes/:caseId/hearing     AI 审理庭
/reviews                      平台审核任务
/reviews/:reviewId            平台审核台
```

### 12.2 争端发起入口

核心组件：

```text
争端描述输入框
订单号 / 售后单号 / 物流单号输入
证据附件上传
发起方身份说明
争议接待官受理分析卡
缺失信息提示
提交按钮
转普通售后提示
```

主提示语：

```text
请描述你要发起的履约争议
```

示例：

```text
物流显示签收，但我没有收到货。
用户退回来的不是我发出的商品。
商品收到时已经破损，商家拒绝退款。
商家说影响二次销售，不给我退款。
```

### 12.3 争端案件工作台

展示：

```text
案件编号
案件状态
发起方
争端类型
双方主张摘要
当前阶段
风险等级
缺失材料
下一步动作
时间线入口
证据工作室入口
AI 审理庭入口
```

### 12.4 AI 证据工作室

核心亮点页面。

组件：

```text
证据卷宗版本选择器
原始证据区
解析结果区
事件时间线
双方主张区
证据缺口区
证据冲突区
Claim-Issue-Evidence Matrix
证据可靠性标记
证据引用跳转
```

支持问答：

```text
这份证据支持哪个主张？
当前还缺什么材料？
哪些证据互相冲突？
哪些证据需要人工重点查看？
```

### 12.5 AI 审理庭

组件：

```text
C1-C6 审理阶段时间线
争点列表
补证请求
证据交叉检查
规则适用说明
AI 主审官草案
Risk Gate 结果
AI 评议团触发状态
五类 Critic 报告
草案修订记录
```

AI 内容必须显示：

```text
生成时间
模型版本
Prompt/Skill 版本
非最终裁决提示
证据引用
置信度
不确定性
```

### 12.6 平台审核台

组件：

```text
ReviewPacket 总览
案件摘要
证据书记官摘要
AI 主审官草案
AI 评议团报告
Remedy Plan
Approval Policy 说明
审核辅助官问答
批准 / 修改后批准 / 退回补证 / 拒绝 / 升级
```

审核辅助官支持问题：

```text
为什么建议这个方案？
评议团提出了哪些风险？
哪些证据最关键？
如果退回补证，需要补哪些材料？
这个案件和历史同类案件是否一致？
```

---

## 13. 错误码与异常策略

### 13.1 错误码

```text
DISPUTE_INVALID_SUBMISSION
DISPUTE_NOT_ADMISSIBLE
CASE_STATE_CONFLICT
DOSSIER_VERSION_CONFLICT
EVIDENCE_PARSE_FAILED
AGENT_OUTPUT_INVALID
AGENT_PERMISSION_DENIED
AGENT_BUDGET_EXCEEDED
AGENT_GUARDRAIL_BLOCKED
DELIBERATION_INCOMPLETE
REVIEW_PACKET_STALE
REVIEW_PERMISSION_DENIED
APPROVAL_REQUIRED
ACTION_HASH_MISMATCH
ACTION_ALREADY_EXECUTED
EXECUTION_RESULT_UNKNOWN
```

### 13.2 异常处理原则

```text
Agent 失败不越过人审。
证据解析失败保留原件。
Critic 失败不视为无异议。
工具结果未知不盲目重试。
输出 Schema 无法修复则中断到人工。
```

---

## 14. 日志、Trace 与审计

### 14.1 统一关联标识

```text
request_id
trace_id
case_id
workflow_id
workflow_run_id
agent_run_id
review_id
action_id
```

### 14.2 必须审计

```text
身份访问
案件状态变化
证据上传与解析
卷宗版本变化
Agent 输入引用
Agent 工具调用
Agent 输出引用
Prompt/Skill/Profile/模型版本
Guardrail 命中
评议团异议
人工审核动作
Tool Executor 请求与响应
外部流水号
```

### 14.3 指标

```text
Workflow 成功率、重试和等待时长
Agent Schema/引用通过率
补证次数、超时和有效率
人审接受、修改、退回和拒绝率
执行成功、幂等命中和结果未知率
模型时延、Token 和成本
相似案件一致性和公平性
```

---

## 15. 安全设计

### 15.1 身份与权限

```text
用户/商家只能访问自身案件。
审核员按角色、队列、金额和风险授权。
服务身份和用户身份分离。
Agent Profile 不能扩大调用方权限。
```

### 15.2 Prompt 注入防护

```text
证据内容作为不可信数据隔离。
Tool 名称和参数由系统注册表限定。
模型输出不能直接形成 Tool Executor 请求。
外部文本中出现的“忽略规则”等指令不得执行。
高风险结果必须人工确认。
```

### 15.3 数据安全

```text
敏感字段按角色脱敏。
日志不记录 API Key、完整证件和支付凭证。
证据对象使用短期签名 URL。
导出、下载和批量查询纳入审计。
```

### 15.4 执行安全

```text
审批与执行职责分离。
执行器校验 action hash。
金额和动作白名单。
Redis 锁与数据库唯一键双重幂等。
结果未知时不得盲目重试。
```

---

## 16. 测试与评估方案

### 16.1 Agent Harness 测试

```text
Agent Profile 权限测试
Context Builder 脱敏测试
Memory Scope 测试
Skill 加载测试
Tool Gateway 权限测试
Loop 停止条件测试
Schema Validator 测试
Guardrail 测试
Hook 调用顺序测试
Trace 完整性测试
```

### 16.2 Agent 业务评估

```text
争议接待官：受理准确率、误收普通查询率。
证据书记官：证据引用准确率、缺证识别准确率。
AI 主审官：争点覆盖率、规则引用准确率、草案通过率。
AI 评议团：重大风险拦截率、无效触发率。
审核辅助官：审核员采纳率、误导率。
Evaluation Agent：改进建议可执行率。
```

### 16.3 Workflow 测试

```text
不予受理终止
简易审理闭环
完整审理 C1-C6
补证等待与恢复
评议团并行与部分失败
人审退回补证
审批 packet 过期
执行幂等
外部结果未知处理
```

### 16.4 安全测试

```text
证据 Prompt 注入
越权案件访问
伪造证据引用
伪造规则引用
诱导 Agent 直接退款
绕过 Human Review
重复执行退款
日志敏感信息泄露
```

### 16.5 E2E 场景

```text
1. 用户只是查物流 → 转普通售后，本系统终止。
2. 商家已同意退款但未执行 → 简易审理 → 人审 → 执行。
3. 签收未收到 → 补证 → 完整审理 → 人审 → 执行。
4. 退货掉包高风险 → AI 主审官 → AI 评议团 → 人审 → 执行。
5. 平台审核员退回补证 → Workflow 暂停恢复 → 更新卷宗 → 继续审理。
6. Tool Executor 超时但外部已成功 → 查询确认 → 避免重复执行。
```

---

## 17. 企业级非功能要求

### 17.1 性能要求

```text
争议接待官常规响应：P95 <= 5s。
证据书记官初版卷宗生成：小型案件 P95 <= 30s。
AI 主审官单 Stage：P95 <= 20s。
AI 评议团：高风险案件 P95 <= 60s，可异步展示。
审核台 ReviewPacket 查询：P95 <= 2s。
```

### 17.2 可用性要求

```text
Agent 服务不可用时，案件必须可转人工。
评议团不可用时，不能阻塞人审，可标记评议不可用。
OCR 不可用时，保留原件并提示人工查看。
Langfuse 不可用时，不影响主流程，但本地审计必须保留。
LiteLLM 不可用时，不得跳过人审或执行。
```

### 17.3 成本控制

```text
每个 Agent 有 token budget。
上下文必须按需检索，不可全量塞入。
评议团按风险触发，不默认全量开启。
重复证据和大附件必须摘要化处理。
Evaluation Agent 离线批处理。
```

### 17.4 可审计性要求

```text
每条事实可回溯证据。
每条规则引用可回溯版本。
每次 Agent 输出可回溯 prompt、skill、model、profile。
每次人工修改可回溯 ReviewPacket 冻结版本。
每次执行可回溯 HumanReviewRecord 和 action_hash。
```

---

## 18. 正式版文档改造要求

Codex 后续如继续优化文档，应遵守：

```text
不扩展为订单中心。
不引入物流监控大盘。
不把普通售后做成主流程。
不把每个 Stage 写成独立 Agent。
不弱化 Agent Harness。
不删除 AI 评议团。
不让 AI 主审官拥有最终裁决权。
不让 Tool Executor 接受未审批动作。
正式版 API 不使用 /v2、/v3 等路径标识。
```

---

## 19. 最终开发基线

正式版最终主线：

```text
FulfillmentDisputeCase
→ Dispute Intake Officer Agent
→ Evidence Clerk Agent
→ Admissibility & Hearing Router
→ SIMPLE_HEARING 或 FULL_HEARING
→ AI Presiding Judge Agent C1-C6
→ AI Deliberation Panel（按需）
→ Remedy Planner
→ Approval Policy Engine
→ Platform Human Review + Review Copilot Agent
→ Tool Executor
→ Case Closure
→ Evaluation Agent
```

最终架构定义：

```text
AI Native 履约争端审理系统不是订单中心，也不是普通客服系统，而是一个以证据卷宗、受控审理、按需 AI 评议团、人审门控和确定性执行为核心的专业化 AI 审理系统。它的技术价值不在于堆叠 Agent 数量，而在于通过 Agent Runtime Harness 精确治理 Agent 的身份、上下文、记忆、工具、Skill、循环、输出、Guardrail、HITL、Trace 和评估闭环。
```
