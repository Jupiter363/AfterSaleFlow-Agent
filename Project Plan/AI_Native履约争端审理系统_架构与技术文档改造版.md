# AI Native 履约争端审理系统：架构与技术文档改造版

> 文档用途：本文件记录最终架构改造原则，并指导 Codex 先统一文档、再完成数据库、后端、Figma、前端和联调。
> 本次任务范围：**文档先行，随后按最终文档实现接口、迁移、Workflow、Figma 页面、Vue 映射和验收。**
> 改造目标：将原“订单履约 / 售后履约 / 争议裁决”方向进一步收敛为一个小而专业的 **AI Native 履约争端审理系统**，突出房间式数字人接力、Agent Harness、AI 审理庭、证据工程、人审门控和按需 AI 评议团。

---

## 0. 本次改造边界

### 0.1 文档先行

Codex 必须先完成以下文档层工作，文档验收通过后才能修改代码：

```text
1. 重写产品定位；
2. 重写总体架构；
3. 重写 Agent 架构；
4. 新增 Agent Harness 工程设计；
5. 重写 Workflow 设计；
6. 重写前端交互建议；
7. 重写核心数据对象设计；
8. 重写工具权限边界；
9. 重写技术文档中的服务分层与模块职责；
10. 删除或弱化普通订单中心、物流监控、泛售后工作台相关内容。
```

### 0.2 文档定版后的代码改造

文档定版后，Codex 必须按顺序执行以下任务：

```text
1. 创建前向数据库迁移和演示争议订单种子；
2. 实现参与方、房间、消息、时钟、事件、证据校验、和解和传票信箱；
3. 补充 Temporal 的 PT2H 举证和 PT3H 庭审时钟；
4. 补充 Java / Python API 与测试；
5. 按逐页提示词完成 Figma 设计；
6. 将通过验收的 Figma 节点映射为 Vue 页面；
7. 完成前后端联调、E2E 和最终验收；
8. 删除旧普通履约路径和与最终房间架构冲突的代码。
```

实现不得绕过强制人审、证据权限、时钟来源或确定性执行边界。

---

## 1. 新产品定位

### 1.1 原定位问题

原架构名称为：

```text
争议裁决驱动的人审门控订单履约协作系统
```

该定位的优点是已经明确了“争议裁决”和“人审门控”，但仍容易被理解为一个较大的订单履约平台，甚至容易扩展成订单中心、物流监控、售后处理大盘等泛化系统。

本次改造需要进一步收敛：

```text
不做订单中心；
不做物流监控；
不做普通售后客服；
只做用户或商家主动发起的履约争端审理。
```

### 1.2 新产品名称

推荐中文名称：

```text
AI Native 履约争端审理系统
```

可选产品化名称：

```text
AI 履约争端审理庭
AI 售后争端审理庭
AI 履约争议审理 Copilot
```

推荐英文名称：

```text
AI Native Fulfillment Dispute Hearing System
```

可选英文产品名：

```text
AI Fulfillment Dispute Court
AI Dispute Hearing Copilot
AI Fulfillment Hearing Copilot
```

### 1.3 新一句话定位

```text
面向用户与商家履约争端的 AI Native 审理协作系统。
系统以履约争端案件为主对象，通过争议接待官、证据书记官、AI 主审官、按需 AI 评议团、审核辅助官和离线复盘官协作，完成争端从受理、举证、审理、评议、人审到确定性执行的闭环。
```

### 1.4 面试 / 项目介绍版本

```text
我设计的是一个 AI Native 履约争端审理系统，不是传统订单中心，也不是普通客服机器人。系统只处理用户或商家主动发起的履约争端，例如签收未收到、退货掉包、商品破损责任不清、少件错发、高价值退款争议等复杂场景。

系统通过争议接待官完成案件受理，通过证据书记官构建证据卷宗，通过 AI 主审官在受控 Workflow 下完成争点归纳、证据缺口识别、补证推进、证据交叉检查、平台规则适用和裁决草案生成。对于高风险案件，系统会按需启动 AI 评议团，从证据、规则、风险、执行方案和公平性五个维度质询主审官草案。最终由平台审核员确认后，Tool Executor 才能执行退款、补发、驳回、关闭售后或继续补证等确定性动作。
```

---

## 2. 系统边界

### 2.1 系统只处理履约争端

本系统只接收以下入口：

```text
用户主动发起履约争端；
商家主动发起履约争端；
平台客服将普通售后升级为履约争端；
平台审核员要求某个售后问题进入争端审理。
```

其中前两个是主入口，后两个是后台辅助入口。

### 2.2 典型处理场景

系统重点处理：

```text
1. 用户称未收到货，但物流显示签收；
2. 用户称商品破损，商家称用户使用后损坏；
3. 用户称少件 / 错发，商家称发货无误；
4. 用户退货后，商家称退回商品被掉包；
5. 用户申请退款，商家拒绝且双方说法冲突；
6. 用户称商家私下承诺退款，但平台记录不完整；
7. 商家称退回商品影响二次销售，用户不认可；
8. 高价值商品退款 / 补发争议；
9. 用户或商家对补证责任、举证责任存在冲突；
10. 平台规则适用不清，需要人工审核确认的复杂履约争议。
```

### 2.3 明确不做的场景

以下场景不作为系统主流程：

```text
1. 普通订单查询；
2. 普通物流查询；
3. 物流监控大盘；
4. 发货时效大盘；
5. 普通催发货；
6. 普通售后进度查询；
7. 无争议退款；
8. 无争议退货；
9. 商家经营数据分析；
10. 库存、仓储、配送调度。
```

这些信息可以作为“证据工具”或“上下文工具”被查询，但不得作为产品主入口和主模块。

---

## 3. 核心设计原则

### 3.1 Workflow 控流程

Workflow 负责长流程、状态和可靠性：

```text
1. 案件状态机；
2. 受理状态；
3. 证据构建状态；
4. 审理阶段状态；
5. 补证等待；
6. 补证超时；
7. 人审暂停与恢复；
8. 评议团触发；
9. 执行重试；
10. 审计落库。
```

### 3.2 Agent 做认知

Agent 只做非结构化、复杂上下文、需要判断和表达的工作：

```text
1. 争端识别；
2. 双方主张理解；
3. 证据材料摘要；
4. 争点归纳；
5. 证据缺口识别；
6. 证据矛盾解释；
7. 平台规则适用说明；
8. 裁决草案生成；
9. 高风险案件多维质询；
10. 审核员辅助解释；
11. 离线复盘。
```

### 3.3 Agent 不做最终裁决

必须坚持：

```text
AI 主审官只生成裁决草案；
AI 评议团只生成质询报告；
审核辅助官只辅助审核员理解；
平台审核员是最终责任锚点；
Tool Executor 只执行已审批动作。
```

### 3.4 Tool 做确定性执行

以下动作必须是确定性工具，不得 Agent 自由执行：

```text
订单查询；
物流查询；
支付查询；
售后查询；
退款执行；
补发执行；
驳回售后；
关闭售后；
创建工单；
发送正式通知；
状态落库；
审计记录。
```

### 3.5 Human Gate 控高风险

涉及以下内容必须进入人审：

```text
退款；
部分退款；
高价值补发；
驳回用户诉求；
关闭售后；
证据不足但金额较高；
疑似欺诈；
退货掉包；
签收未收到；
规则适用不确定；
AI 主审官置信度较低；
AI 评议团提出重大风险。
```

---

## 4. 总体架构改造

### 4.1 新总体架构图

```text
用户 / 商家 / 平台客服 / 平台审核员
        ↓
争端发起入口
Web / App / 商家后台 / 工单系统
        ↓
A. Dispute Intake Officer Agent
争议接待官：受理判断、争端识别、主张抽取
        ↓
FulfillmentDisputeCase
创建履约争端案件
        ↓
B. Evidence Clerk Agent
证据书记官：证据卷宗、时间线、主张、缺证、矩阵
        ↓
Admissibility & Hearing Router
受理门控与审理路径判断
        ↓
┌──────────────────────────────────────┐
│ 路径一：不予受理并留档                   │
│ Not Admissible / Transfer             │
└──────────────────────────────────────┘
        ↓
┌──────────────────────────────────────┐
│ 路径二：简易审理流                     │
│ Simple Hearing Flow                   │
│ 规则明确、证据充分、风险较低              │
└──────────────────────────────────────┘
        ↓
┌──────────────────────────────────────┐
│ 路径三：完整争端审理流                  │
│ Full Dispute Hearing Flow             │
│ AI 主审官 + 必要时 AI 评议团             │
└──────────────────────────────────────┘
        ↓
D. Remedy Planner
执行方案规划
        ↓
Approval Policy Engine
审批策略、风险分级、审核路径
        ↓
Platform Human Review + Review Copilot Agent
平台审核员最终确认，审核辅助官提供解释
        ↓
Tool Executor
退款、补发、驳回、关闭售后、继续补证、通知双方
        ↓
Case Closure
案件闭环、审计记录
        ↓
Evaluation Agent
离线复盘、质量评估、规则缺口发现、Skill 优化
```

### 4.2 架构主线说明

系统从争端发起入口开始，由争议接待官判断是否构成履约争端。只有构成履约争端的请求才创建 `FulfillmentDisputeCase`。

随后证据书记官构建证据卷宗，包括订单、物流、支付、售后、聊天、仓储、质检、用户材料、商家材料等证据快照。

路由节点不再是泛化的 Dispute Router，而是 `Admissibility & Hearing Router`，用于判断：

```text
1. 不予受理并留档；
2. 简易审理；
3. 完整争端审理。
```

完整争端审理由 AI 主审官在 `DisputeHearingWorkflow` 控制下完成 C1-C6 阶段。若案件命中高风险条件，则触发 AI 评议团进行多维质询。

所有处理结论最终统一进入：

```text
Remedy Planner
  ↓
Approval Policy Engine
  ↓
Platform Human Review
  ↓
Tool Executor
```

该统一后半段是系统安全性、可审计性和可追责性的核心。

---

## 5. Agent 架构：AI 履约争端审理庭

### 5.1 Agent 总览

```text
常驻线上 Agent：
1. Dispute Intake Officer Agent     争议接待官
2. Evidence Clerk Agent             证据书记官
3. AI Presiding Judge Agent         AI 主审官
4. Review Copilot Agent             审核辅助官

按需 Agent：
5. AI Deliberation Panel            AI 评议团
   - Evidence Critic                证据质询员
   - Rule Critic                    规则质询员
   - Risk Critic                    风险质询员
   - Remedy Critic                  执行方案质询员
   - Fairness Critic                公平性 / 一致性质询员

离线 Agent：
6. Evaluation Agent                 离线复盘官
```

---

## 6. Agent 详细设计

### 6.1 争议接待官 Agent

英文名：

```text
Dispute Intake Officer Agent
```

定位：

```text
履约争端入口受理 Agent。
它负责判断用户或商家的输入是否构成履约争端，而不是普通订单咨询或普通售后咨询。
```

核心职责：

```text
1. 判断输入是否构成履约争端；
2. 识别发起方是用户还是商家；
3. 抽取订单号、售后单号、物流单号；
4. 识别用户主张；
5. 识别商家主张；
6. 判断是否存在双方冲突；
7. 判断是否缺失基础信息；
8. 判断是否可受理；
9. 生成 FulfillmentDisputeCaseDraft；
10. 输出初始风险等级。
```

输出示例：

```json
{
  "admissibility": "accepted",
  "dispute_type": "signed_not_received",
  "initiator": "user",
  "user_claim": "物流显示签收但本人未收到",
  "merchant_position": "not_submitted",
  "order_id": "O-10086",
  "missing_information": ["delivery_contact_record"],
  "initial_risk_level": "high",
  "next_step": "build_evidence_dossier"
}
```

禁止行为：

```text
1. 不得判断谁对谁错；
2. 不得承诺退款；
3. 不得承诺补发；
4. 不得关闭售后；
5. 不得给出最终裁决；
6. 不得替平台审核员表态。
```

---

### 6.2 证据书记官 Agent

英文名：

```text
Evidence Clerk Agent
```

定位：

```text
履约争端证据卷宗构建 Agent。
它是 AI 证据工作室的核心，不负责裁决，只负责把混乱的材料变成可审理的证据卷宗。
```

核心职责：

```text
1. 构建 EvidenceDossier；
2. 收集订单证据；
3. 收集物流证据；
4. 收集支付证据；
5. 收集售后证据；
6. 收集平台聊天记录；
7. 收集仓储发货记录；
8. 收集退货验收记录；
9. 收集质检记录；
10. 解析用户上传材料；
11. 解析商家上传材料；
12. 生成案件时间线；
13. 整理双方主张；
14. 标注证据来源和可靠性；
15. 识别证据缺口；
16. 生成初步争点候选；
17. 构建 Claim-Issue-Evidence Matrix。
```

输出示例：

```json
{
  "dossier_id": "ED-10086",
  "case_id": "DC-10086",
  "timeline_ready": true,
  "party_claims": [
    {
      "party": "user",
      "claim": "物流显示签收但本人未收到"
    }
  ],
  "evidence_items": [
    {
      "evidence_id": "EV-001",
      "type": "logistics_trace",
      "source": "platform",
      "reliability": "high"
    }
  ],
  "missing_evidence": [
    "签收照片",
    "用户与快递沟通截图"
  ],
  "policy_candidates": [
    "签收异常处理规则"
  ]
}
```

禁止行为：

```text
1. 不得裁决责任；
2. 不得判断最终结果；
3. 不得生成退款方案；
4. 不得生成补发方案；
5. 不得替用户或商家辩护；
6. 不得选择性隐藏不利证据。
```

---

### 6.3 AI 主审官 Agent

英文名：

```text
AI Presiding Judge Agent
```

定位：

```text
AI 主审官是履约争端审理的核心认知 Agent。
它在 Workflow 控制下完成争点、证据、规则和裁决草案推理，但不是最终裁决者。
```

核心职责：

```text
1. 阅读 EvidenceDossier；
2. 归纳争点；
3. 识别关键证据缺口；
4. 生成补证请求；
5. 分析证据支持关系；
6. 分析证据冲突关系；
7. 适用平台规则；
8. 生成事实认定草案；
9. 生成证据评价草案；
10. 生成规则适用说明；
11. 生成裁决建议；
12. 标注置信度；
13. 生成审核员关注点；
14. 生成建议 Remedy 方向。
```

#### C1-C6 阶段改造

C1-C6 不再是六个独立 Agent，而是 AI 主审官在 `DisputeHearingWorkflow` 控制下执行的六个审理 Stage：

```text
C1 Issue Framing Stage
C2 Evidence Gap Stage
C3 Evidence Request Stage
C4 Evidence Cross-check Stage
C5 Rule Application Stage
C6 Draft Generation Stage
```

每个 Stage 均遵循：

```text
Workflow 读取 HearingState
  ↓
Harness 构建上下文
  ↓
加载对应 Skill
  ↓
AI 主审官执行阶段推理
  ↓
输出结构化 Stage Result
  ↓
Output Validator 校验
  ↓
写入 HearingRecord
  ↓
Workflow 决定下一阶段
```

禁止行为：

```text
1. 不得输出最终裁决；
2. 不得直接退款；
3. 不得直接补发；
4. 不得关闭售后；
5. 不得绕过平台审核员；
6. 不得创造平台规则；
7. 不得替任一方辩护；
8. 不得使用法律判决式表达伪装成最终结果。
```

---

### 6.4 AI 评议团 Agent Pool

英文名：

```text
AI Deliberation Panel
```

定位：

```text
AI 评议团是高风险履约争端的按需质询机制。
它不参与普通案件，只在高价值、低置信度、证据冲突严重或规则适用不确定的案件中触发。
```

触发条件：

```text
1. 退款金额超过阈值；
2. 补发高价值商品；
3. 退货掉包争议；
4. 签收未收到争议；
5. 证据冲突数量超过阈值；
6. AI 主审官置信度低于阈值；
7. 用户或商家历史风险高；
8. 规则适用存在冲突；
9. 审核员主动要求复核；
10. Remedy Plan 影响较大或不可逆。
```

评议团成员：

```text
1. Evidence Critic：证据质询员；
2. Rule Critic：规则质询员；
3. Risk Critic：风险质询员；
4. Remedy Critic：执行方案质询员；
5. Fairness Critic：公平性 / 一致性质询员。
```

#### Evidence Critic

只审查证据链：

```text
证据是否支持主张；
证据之间是否矛盾；
是否遗漏关键证据；
是否存在过度解释；
哪些证据需要人工重点查看。
```

#### Rule Critic

只审查规则适用：

```text
规则版本是否正确；
是否遗漏平台规则、商家规则、类目规则、SLA；
是否把常识当成平台规则；
当前规则条件是否真正满足。
```

#### Risk Critic

只审查风险：

```text
是否高价值退款；
是否疑似恶意退款；
是否存在频繁退款风险；
是否存在舆情、法务、风控风险；
是否必须升级人工重点审核。
```

#### Remedy Critic

只审查执行方案：

```text
退款、部分退款、补发、继续补证、驳回哪个更稳；
执行动作是否与证据和规则一致；
是否存在不可逆副作用；
是否需要额外确认。
```

#### Fairness Critic

只审查公平性和一致性：

```text
当前草案是否偏向用户或商家；
同类历史案件是否处理一致；
是否存在同案不同判风险；
举证责任是否分配合理；
对双方的说明是否中立。
```

评议团输出：

```json
{
  "deliberation_id": "DL-10086",
  "case_id": "DC-10086",
  "trigger_reason": [
    "signed_not_received",
    "low_confidence",
    "evidence_conflict"
  ],
  "critic_reports": [
    {
      "critic": "EvidenceCritic",
      "risk_level": "high",
      "finding": "缺少签收照片，当前证据不足以直接支持最终退款方案"
    }
  ],
  "panel_summary": {
    "panel_result": "revision_required",
    "major_risks": [
      "签收证明缺失",
      "规则适用仍存在待证条件"
    ],
    "recommended_revision": "建议将草案调整为继续补证后人工重点审核"
  }
}
```

禁止行为：

```text
1. 不得直接修改最终裁决；
2. 不得审批；
3. 不得执行动作；
4. 不得绕过 AI 主审官和平台审核员；
5. 不得输出对用户或商家的最终处理结果。
```

---

### 6.5 审核辅助官 Agent

英文名：

```text
Review Copilot Agent
```

定位：

```text
服务平台审核员的人审辅助 Agent。
它不替审核员决策，只帮助审核员理解案件、证据、草案、评议报告和执行方案。
```

核心职责：

```text
1. 解释 AI 主审官裁决草案；
2. 解释 AI 评议团质询结果；
3. 压缩关键证据摘要；
4. 回答审核员追问；
5. 对比不同 Remedy 方案；
6. 提示审核重点；
7. 辅助生成审核意见；
8. 辅助生成用户 / 商家通知文案。
```

禁止行为：

```text
1. 不得替审核员批准；
2. 不得替审核员驳回；
3. 不得触发执行；
4. 不得修改审计事实；
5. 不得隐藏评议团提出的风险。
```

---

### 6.6 离线复盘官 Evaluation Agent

英文名：

```text
Evaluation Agent
```

定位：

```text
离线复盘与持续优化 Agent，不参与当前案件实时审理。
```

核心职责：

```text
1. 评估 AI 主审官草案通过率；
2. 统计平台审核员修改率；
3. 分析证据书记官缺证识别准确率；
4. 评估 AI 评议团触发是否有效；
5. 统计评议团有效拦截率；
6. 分析 Fairness Critic 是否降低同案不同判；
7. 沉淀争议案例测试集；
8. 发现平台规则缺口；
9. 发现 Prompt / Skill / Harness 缺陷；
10. 生成优化建议。
```

---

## 7. Agent Harness 工程设计

### 7.1 Harness 总体定位

本系统必须把 Agent Harness 作为一等公民，而不是简单封装 LLM API。

Agent Harness 是模型之外的工程运行时，负责：

```text
身份和权限；
指令和策略；
上下文管理；
记忆管理；
Skill 加载；
工具网关；
Agent 循环控制；
结构化输出校验；
风险控制；
人审中断；
生命周期 Hook；
可观测性；
离线评估回流。
```

### 7.2 Harness 层结构

```text
Agent Runtime Harness Layer
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

---

### 7.3 Agent Identity & Authority Profile

每个 Agent 必须配置身份、职责、权限和禁止行为。

配置项：

```text
agent_id
agent_name
agent_role
allowed_tasks
forbidden_tasks
allowed_tools
forbidden_tools
memory_scope
max_iterations
max_tool_calls
max_runtime_seconds
risk_level
requires_human_approval_for
output_schema
```

示例：

```yaml
agent_id: ai_presiding_judge_agent
agent_name: AI 主审官
role: 生成履约争端审理草案
allowed_tasks:
  - issue_framing
  - evidence_gap_analysis
  - evidence_cross_check
  - rule_application
  - draft_generation
forbidden_tasks:
  - final_decision
  - refund_execution
  - replacement_execution
  - close_after_sales
  - bypass_human_review
allowed_tools:
  - read_evidence_dossier
  - search_policy
  - search_similar_cases
  - read_hearing_state
forbidden_tools:
  - create_refund
  - create_replacement
  - close_after_sales
max_iterations: 5
max_tool_calls: 10
requires_human_approval_for:
  - refund
  - replacement
  - reject
  - close_case
output_schema: AdjudicationDraftResult
```

---

### 7.4 Instruction & Policy Layer

Prompt 不能写成一个大段文本，必须分层管理。

结构：

```text
Global Safety Policy
  ↓
Product Boundary Policy
  ↓
Agent Role Instruction
  ↓
Stage-specific Instruction
  ↓
Skill Instruction
  ↓
Runtime Constraints
```

示例：

```text
Global Safety Policy:
你是平台履约争端审理系统中的受控 Agent，不得直接执行任何影响用户权益或商家成本的动作。

Product Boundary:
本系统只处理用户或商家主动发起的履约争端，不处理普通订单查询和物流监控。

Agent Role:
你是 AI 主审官，只负责生成审理草案，不是最终裁决者。

Stage:
当前阶段是 Evidence Cross-check Stage，请只分析证据支持、冲突和缺口。

Skill:
当前使用 SignedNotReceivedSkill，需要关注签收证明、签收人、代收点、快递沟通记录。

Runtime:
必须输出 JSON，不得输出最终裁决，不得承诺退款。
```

---

### 7.5 Context Assembly Layer

上下文构建是 Harness 的核心能力。

Context Builder 根据以下参数组装上下文：

```text
agent_name
workflow_stage
case_type
risk_level
context_budget
user_role
skill_id
```

上下文分层：

```text
必带上下文：
- case_id
- dispute_type
- current_stage
- current_risk_level
- initiator_role
- allowed_actions
- forbidden_actions

阶段上下文：
- 当前 HearingState
- 当前 Stage 输入
- 上一阶段结构化输出
- 当前 Skill 指令

证据上下文：
- EvidenceDossier 摘要
- PartyClaims
- Timeline
- MissingEvidence
- Claim-Issue-Evidence Matrix

可检索上下文：
- 原始聊天记录
- 物流原始轨迹
- 证据文件原文
- 平台规则全文
- 相似历史案件

禁止直接注入上下文：
- 未脱敏手机号 / 地址 / 身份信息
- 与当前案件无关的历史争议
- 无关订单明细
- 大段原始附件全文
```

每次 Agent 调用必须记录：

```text
上下文来源；
上下文版本；
是否脱敏；
token 预算；
上下文摘要；
被丢弃的上下文类型。
```

---

### 7.6 Memory Layer

Memory 不能替代数据库和 Workflow State。

核心原则：

```text
数据库和 Workflow State 是事实源；
Memory 只做上下文增强。
```

Memory 分层：

```text
Run Memory:
单次 Agent run 内的临时工具结果和中间观察。

Case Memory:
当前 FulfillmentDisputeCase 的案件摘要、关键争点、补证历史、草案版本。

Hearing Memory:
DisputeHearingWorkflow 内的阶段状态、轮次、等待点、历史 Stage 输出。

Domain Memory:
平台规则摘要、争端类型模板、证据要求模板、相似案例模式。

Evaluation Memory:
人审修改、执行结果、失败模式、评议团有效性、Prompt/Skill 版本效果。
```

---

### 7.7 Skill Library Layer

场景差异不通过增加 Agent 解决，而通过 Skill 承载。

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

每个 Skill 必须包含：

```text
skill_id
适用场景
输入要求
必要证据
审理步骤
风险信号
输出 schema
禁止行为
示例案件
评估标准
```

示例：

```text
SignedNotReceivedSkill

适用：
物流显示签收，但用户反馈未收到。

必要证据：
物流轨迹、签收时间、签收人、签收证明、签收照片、用户快递沟通截图、商家发货凭证。

审理步骤：
1. 判断物流是否显示签收；
2. 判断是否本人签收；
3. 判断是否代收点 / 门卫 / 前台签收；
4. 判断是否有签收照片；
5. 判断用户是否提供快递沟通记录；
6. 判断是否需要继续补证或进入人工重点审核。

禁止：
不得直接认定用户虚假陈述；
不得直接承诺退款；
不得把物流签收简单等同于用户实际收到。
```

---

### 7.8 Tool Gateway Layer

Agent 不能直接调用真实业务系统，必须通过 Tool Gateway。

工具分级：

```text
Read Tools:
query_order
query_logistics
query_payment
query_after_sales
query_chat_record
query_policy
query_similar_cases

Draft Tools:
draft_evidence_request
draft_notification
draft_review_packet
draft_adjudication_summary

Write Tools:
create_evidence_request
append_evidence_item
create_review_task
save_hearing_record

Execution Tools:
create_refund
create_replacement
reject_after_sales
close_after_sales
notify_user
notify_merchant
```

权限原则：

```text
Agent 可以调用 Read Tools；
Agent 可以调用 Draft Tools；
Agent 调用 Write Tools 必须经过 Workflow 授权；
Agent 永远不能直接调用 Execution Tools；
Execution Tools 只能由 Tool Executor 在审批通过后调用。
```

每次工具调用必须经过：

```text
schema 校验；
参数校验；
权限校验；
幂等 key；
风险分级；
审批检查；
调用审计；
失败重试；
结果脱敏。
```

---

### 7.9 Agent Loop Controller

每个 Agent 运行时必须是受控循环。

统一循环：

```text
1. load_agent_profile
2. read_workflow_state
3. read_memory
4. build_context
5. load_skill
6. call_model
7. parse_structured_output
8. validate_output_schema
9. check_guardrails
10. call_allowed_tool_if_needed
11. observe_tool_result
12. update_run_memory
13. check_stop_condition
14. return_result_to_workflow
```

停止条件：

```text
目标完成；
无更多工具调用；
达到最大迭代次数；
达到最大工具调用次数；
达到最大 token budget；
达到最大耗时；
重复调用同一工具；
schema 校验多次失败；
命中高风险动作；
需要人工确认；
置信度低于阈值。
```

---

### 7.10 Structured Output & Validation Layer

所有 Agent 输出必须结构化。

统一输出 envelope：

```json
{
  "agent_name": "AI_Presiding_Judge_Agent",
  "run_id": "AR-10086",
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

必须定义以下输出 Schema：

```text
DisputeIntakeResult
EvidenceDossierResult
IssueFramingResult
EvidenceGapResult
EvidenceRequestResult
EvidenceCrossCheckResult
RuleApplicationResult
AdjudicationDraftResult
DeliberationReportResult
ReviewCopilotResult
EvaluationReportResult
```

---

### 7.11 Guardrail & Risk Control Layer

Guardrail 包含内容安全和业务安全。

必须实现：

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

---

### 7.12 Human-in-the-loop / Interrupt Layer

中断类型：

```text
need_user_evidence
need_merchant_evidence
need_platform_review
need_risk_review
approval_required
draft_revision_required
manual_takeover_required
```

审批卡 payload：

```json
{
  "interrupt_type": "approval_required",
  "case_id": "DC-10086",
  "action_type": "partial_refund",
  "amount": 349.50,
  "reason_summary": "...",
  "evidence_summary": "...",
  "risk_flags": ["high_value", "evidence_conflict"],
  "allowed_decisions": [
    "approve",
    "reject",
    "edit",
    "request_more_evidence",
    "escalate"
  ]
}
```

---

### 7.13 Lifecycle Hooks / Middleware Layer

必须设计生命周期 Hook：

```text
before_agent_run:
初始化 run_id、权限、预算、trace。

before_context_build:
决定上下文策略和脱敏策略。

after_context_build:
记录上下文 token、来源、摘要。

before_llm_call:
注入 instruction、skill、工具清单。

after_llm_call:
记录 token、延迟、模型输出摘要。

before_tool_call:
工具权限、参数、幂等、审批检查。

after_tool_call:
记录工具结果、错误、重试信息。

before_memory_read:
判断读取哪些 memory。

after_memory_write:
写入案件摘要、证据摘要、评估信号。

before_human_interrupt:
构造审批卡 payload。

after_human_resume:
写入人工决策并恢复 workflow。

on_guardrail_violation:
降级、转人工、拒绝执行。

on_agent_error:
重试、回滚、失败诊断。

after_agent_run:
输出结构化结果、trace、评估样本。
```

---

### 7.14 Observability & Trace Layer

必须记录：

```text
workflow_trace_id
agent_run_id
case_id
stage
model_name
prompt_version
skill_version
context_sources
tool_calls
tool_args_hash
tool_results_summary
guardrail_events
human_interrupts
human_decisions
output_schema_validation
token_usage
latency
cost
confidence
final_action_result
```

推荐在技术文档中指定：

```text
Langfuse Trace
Application AuditLog
Workflow History
Human Review Record
Evaluation Dataset
```

---

## 8. Workflow 设计

### 8.1 主 Workflow：FulfillmentDisputeWorkflow

状态：

```text
SUBMITTED
INTAKE_PROCESSING
ADMISSIBILITY_CHECKING
EVIDENCE_DOSSIER_BUILDING
HEARING_ROUTING
NOT_ADMISSIBLE
ACCEPTED
WAITING_USER_EVIDENCE
WAITING_MERCHANT_EVIDENCE
DELIBERATION_PANEL_REVIEW
DRAFT_REVISION
REMEDY_PLANNING
APPROVAL_CHECKING
WAITING_HUMAN_REVIEW
EXECUTING
CLOSED
REJECTED_NOT_ADMISSIBLE
MANUAL_TAKEOVER
FAILED
```

主流程：

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

---

### 8.2 DisputeHearingWorkflow

```text
DisputeHearingWorkflow
│
├── C0 Hearing Controller
│
├── C1 Issue Framing Stage
│   └── AI 主审官 + IssueFramingSkill
│
├── C2 Evidence Gap Stage
│   └── AI 主审官 + EvidenceGapSkill
│
├── C3 Evidence Request Stage
│   └── AI 主审官生成补证请求
│   └── Workflow 创建补证任务
│
├── WAITING_USER_OR_MERCHANT_EVIDENCE
│
├── C4 Evidence Cross-check Stage
│   └── AI 主审官 + CrossCheckSkill
│
├── C5 Rule Application Stage
│   └── AI 主审官 + RuleApplicationSkill
│
├── C6 Draft Generation Stage
│   └── AI 主审官 + DraftGenerationSkill
│
├── Risk Gate
│
├── Deliberation Panel Stage
│   └── 按需并行运行 Critic Agents
│
└── Draft Revision Stage
    └── AI 主审官吸收评议结果修订草案
```

---

### 8.3 DeliberationPanelWorkflow

```text
DeliberationPanelWorkflow
│
├── Load AdjudicationDraft
├── Load EvidenceDossier
├── Load RuleApplicationResult
├── Load RemedyPlan Candidate
├── Run EvidenceCritic in parallel
├── Run RuleCritic in parallel
├── Run RiskCritic in parallel
├── Run RemedyCritic in parallel
├── Run FairnessCritic in parallel
├── Aggregate Critic Reports
├── Generate DeliberationReport
└── Return to DisputeHearingWorkflow
```

---

## 9. 三类审理路径

### 9.1 不予受理并留档

适用：

```text
用户只是查物流；
用户只是问退款进度；
没有双方主张冲突；
没有商家参与；
不属于履约争端；
订单号缺失且无法补全；
争议描述无效或与平台履约无关。
```

输出：

```text
NotAdmissibleResult
TransferToSupportResult
NeedMoreBasicInfoResult
```

### 9.2 简易审理流

适用：

```text
双方有争议但规则明确；
证据充分；
不需要多轮补证；
风险较低；
不需要 AI 评议团。
```

例如：

```text
订单未发货但商家拒绝退款；
退货已签收但商家超时未处理；
商家聊天记录明确同意退款但未执行。
```

流程：

```text
争议接待官
  ↓
证据书记官
  ↓
简易审理
  ↓
Remedy Planner
  ↓
Approval Policy Engine
  ↓
Platform Human Review
  ↓
Tool Executor
```

### 9.3 完整争端审理流

适用：

```text
签收未收到；
退货掉包；
商品破损责任不清；
少件错发责任不清；
高价值退款争议；
用户 / 商家说法冲突；
证据不完整；
规则适用不确定。
```

流程：

```text
争议接待官
  ↓
证据书记官
  ↓
AI 主审官 C1-C6
  ↓
Risk Gate
  ↓
必要时 AI 评议团
  ↓
草案修订
  ↓
Remedy Planner
  ↓
Approval Policy Engine
  ↓
平台审核员 + 审核辅助官
  ↓
Tool Executor
```

---

## 10. 前端交互建议

前端只保留 5 个核心界面。

### 10.1 争议发起入口

页面标题：

```text
发起履约争端
```

主输入框：

```text
请描述你要发起的履约争议
```

示例：

```text
物流显示签收，但我没有收到货
用户退回来的不是我发出的商品
商品收到时已经破损，商家拒绝退款
商家说影响二次销售，不给我退款
```

页面组件：

```text
争议描述输入框
订单号 / 售后单号 / 物流单号输入
附件上传
发起方身份选择
争议接待官受理结果
缺失信息提示
是否进入审理提示
```

---

### 10.2 争议案件工作台

展示：

```text
案件状态
发起方
被发起方
争议类型
用户主张
商家主张
当前审理阶段
缺失材料
处理时限
风险等级
下一步动作
```

---

### 10.3 AI 证据工作室

展示：

```text
证据卷宗列表
原始材料区
OCR / 文件解析结果
事件时间线
双方主张区
证据缺口区
Claim-Issue-Evidence Matrix
证据可靠性标注
证据与争点关联图
```

支持提问：

```text
这份证据支持哪个主张？
当前还缺什么材料？
哪些证据互相冲突？
哪些证据需要人工重点看？
```

---

### 10.4 AI 审理庭

展示：

```text
AI 主审官审理进度
C1-C6 阶段时间线
争点列表
补证请求
证据交叉检查结果
规则适用说明
裁决草案
AI 评议团触发状态
AI 评议团质询报告
草案修订记录
```

核心交互：

```text
查看争点
查看证据缺口
查看补证请求
查看规则适用
查看裁决草案
查看评议团质询
查看草案修订前后对比
```

---

### 10.5 平台审核台

展示：

```text
Review Packet
案件摘要
证据书记官摘要
AI 主审官草案
AI 评议团报告
Remedy Plan
风险提示
审核辅助官问答
批准 / 修改 / 驳回 / 要求补证 / 转人工 / 转风控
```

审核辅助官支持问题：

```text
为什么推荐这个处理方案？
哪些证据最关键？
评议团提出了哪些风险？
如果要求继续补证，会影响什么？
这个案件和历史同类案件是否一致？
```

---

## 11. 数据对象设计

核心对象：

```text
fulfillment_dispute_case
dispute_submission
party_claim
evidence_dossier
evidence_item
case_timeline
issue
claim_issue_evidence_matrix
evidence_request
hearing_state
hearing_record
adjudication_draft
deliberation_report
review_packet
remedy_plan
approval_record
action_record
evaluation_trace
agent_run_trace
agent_memory
skill_version
prompt_version
```

证据快照对象：

```text
order_evidence_snapshot
logistics_evidence_snapshot
payment_evidence_snapshot
after_sales_evidence_snapshot
chat_evidence_snapshot
warehouse_evidence_snapshot
inspection_evidence_snapshot
```

### 11.1 FulfillmentDisputeCase

```text
case_id
order_id
user_id
merchant_id
initiator_role
dispute_type
case_status
risk_level
current_stage
created_at
updated_at
```

### 11.2 DisputeSubmission

```text
submission_id
case_id
initiator_role
raw_text
attachments
submitted_at
channel
```

### 11.3 PartyClaim

```text
claim_id
case_id
party
claim_content
claim_type
source
created_at
```

### 11.4 EvidenceDossier

```text
dossier_id
case_id
timeline
platform_evidence
user_evidence
merchant_evidence
missing_evidence
evidence_reliability
policy_candidates
claim_issue_evidence_matrix
created_by_agent
created_at
updated_at
```

### 11.5 DeliberationReport

```text
deliberation_id
case_id
draft_id
trigger_reason
evidence_critic_report
rule_critic_report
risk_critic_report
remedy_critic_report
fairness_critic_report
panel_summary
recommended_revision
created_at
```

### 11.6 ReviewPacket

```text
packet_id
case_id
evidence_clerk_summary
presiding_judge_draft
deliberation_report
risk_gate_result
fairness_summary
remedy_plan
reviewer_attention
review_status
created_at
```

---

## 12. 技术服务分层建议

### 12.1 Java / Spring Boot 文档职责

文档中应描述 Java 侧负责确定性业务能力：

```text
FulfillmentDisputeCase Service
Evidence Metadata Service
Hearing State Service
Review Service
Approval Policy Engine
Tool Executor
AuditLog Service
Permission Service
Idempotency Service
```

### 12.2 Workflow Worker 文档职责

```text
FulfillmentDisputeWorkflow
DisputeHearingWorkflow
DeliberationPanelWorkflow
HumanReviewWorkflow
ExecutionWorkflow
EvaluationEmitWorkflow
```

### 12.3 Python / Agent Service 文档职责

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

### 12.4 Evidence Parser Service 文档职责

```text
OCR
PDF / Word / Excel 解析
图片元数据提取
视频关键帧抽取
证据脱敏
文件哈希
重复证据检测
```

### 12.5 Observability 文档职责

```text
Langfuse Trace
Application AuditLog
Workflow History
Human Review Record
Evaluation Dataset
```

---

## 13. 文档改造执行清单

### 13.1 必须删除或弱化

```text
普通订单中心
全量订单管理
物流监控大盘
泛售后工作台
自动化策略中心
普通客服机器人定位
普通履约流作为主线
```

### 13.2 必须新增或强化

```text
AI Native 履约争端审理系统定位
FulfillmentDisputeCase 主对象
争议接待官 Agent
证据书记官 Agent
AI 主审官 Agent
AI 评议团 Agent Pool
审核辅助官 Agent
Agent Runtime Harness Layer
AI 证据工作室
AI 审理庭
平台审核台
DeliberationReport
Fairness Critic
```

### 13.3 原架构中必须保留

```text
Human-Gated Agentic Workflow
C 层受控审理 Workflow 思想
B 层不裁决原则
D 层不重新断案原则
Approval Policy Engine
Platform Human Review
Tool Executor
Evaluation Agent 离线复盘
Agent 与非 Agent 边界
工具权限矩阵
```

---

## 14. 新文档建议目录

建议 Codex 将架构文档重构为：

```text
0. 文档定位
1. 项目最终定位：AI Native 履约争端审理系统
2. 系统边界：只处理履约争端
3. 业务对象设计
   3.1 FulfillmentDisputeCase
   3.2 PartyClaim
   3.3 EvidenceDossier
   3.4 Issue
   3.5 DeliberationReport
   3.6 ReviewPacket
4. 总体架构
   4.1 总体架构图
   4.2 主流程说明
5. AI 履约争端审理庭 Agent 架构
   5.1 争议接待官 Agent
   5.2 证据书记官 Agent
   5.3 AI 主审官 Agent
   5.4 AI 评议团 Agent Pool
   5.5 审核辅助官 Agent
   5.6 Evaluation Agent
6. Agent Harness 工程设计
   6.1 Identity & Authority
   6.2 Instruction & Policy
   6.3 Context Assembly
   6.4 Memory Layer
   6.5 Skill Library
   6.6 Tool Gateway
   6.7 Agent Loop Controller
   6.8 Output Validation
   6.9 Guardrails
   6.10 HITL Interrupt
   6.11 Lifecycle Hooks
   6.12 Observability
   6.13 Evaluation Feedback
7. Workflow 设计
   7.1 FulfillmentDisputeWorkflow
   7.2 DisputeHearingWorkflow
   7.3 DeliberationPanelWorkflow
   7.4 HumanReviewWorkflow
   7.5 ExecutionWorkflow
8. 三类审理路径
   8.1 不予受理并留档
   8.2 简易审理流
   8.3 完整争端审理流
9. Remedy Planner
10. Approval Policy Engine
11. Platform Human Review
12. Tool Executor
13. 前端交互设计
14. 数据对象设计
15. 工具权限矩阵
16. 典型场景流转
17. 评估与复盘闭环
18. 最终架构总结
```

---

## 15. 最终架构总结

改造后的系统不再是订单中心，不再是物流监控系统，也不是普通售后客服系统。

它是：

```text
AI Native 履约争端审理系统。
```

最终架构一句话：

```text
系统以用户或商家主动发起的履约争端为唯一入口，以 FulfillmentDisputeCase 为主对象，通过争议接待官完成受理，通过证据书记官构建证据卷宗，通过 AI 主审官在 Workflow 控制下完成争点归纳、证据缺口识别、补证推进、证据交叉检查、规则适用和裁决草案生成；高风险案件触发 AI 评议团从证据、规则、风险、执行方案和公平性五个维度质询草案；最终由平台审核员在人审门控下确认，Tool Executor 只执行已审批动作，Evaluation Agent 负责离线复盘和持续优化。
```

技术亮点：

```text
1. 履约争端入口极度收敛，避免泛订单中心；
2. AI 证据书记官将混乱材料结构化为证据卷宗；
3. AI 主审官在受控 Workflow 中完成多阶段审理；
4. AI 评议团按需触发，形成高风险案件的多维质询机制；
5. Agent Harness 成为一等公民，体现上下文、记忆、工具、权限、Hook、观测、评估等工程深度；
6. Human-in-the-loop 是高风险动作的责任锚点；
7. Tool Executor 保证真实业务动作确定、可审计、可追责；
8. Evaluation Agent 离线复盘，形成持续优化闭环。
```

---

## 16. 房间式数字人交互架构（最终补充基线）

本节优先级高于本文早期的页面级建议。最终产品不是单一 AI 工作台，而是数字人接力办理的一组业务房间。

### 16.1 最终产品故事

```text
争议办理总览
→ 争议接待室
→ 受理并邀请用户与商家
→ 证据书记官室（PT2H）
→ 小法庭（PT3H，最多三轮）
→ 按需 AI 评审团
→ 平台终审
→ Tool Executor
→ 结果与离线复盘
```

争议办理总览只展示两类记录：

```text
EXTERNAL_IMPORT：外部接口导入的争议订单；
INTAKE_CREATED：通过争议接待官创建的争议订单。
```

首版允许用数据库种子模拟外部系统已导入的争议订单，但必须保留 `(source_system, external_case_ref)` 幂等导入契约。

### 16.2 业务房间

| 房间 | 数字人 | 参与者 | 主要结果 |
|---|---|---|---|
| INTAKE | 争议接待官 | 发起方 | 受理建议、结构化引用、双方主张、诉求、风险 |
| EVIDENCE | 证据书记官 | 用户、商家、授权平台人员 | 分级证据、可信度、冻结卷宗 |
| HEARING | 证据书记官、AI 主审官、按需评审团 | 用户、商家、审核员只读旁观 | 三轮庭审、和解或非最终裁决草案 |
| REVIEW | 审核辅助官 | 平台审核员 | 人类最终决定与审核记录 |

房间状态统一为：

```text
LOCKED | OPEN | WAITING | SEALED | CLOSED
```

数字人状态统一为：

```text
IDLE | LISTENING | THINKING | SPEAKING | COMPLETED | HANDOFF | ERROR
```

### 16.3 时效与强制收敛

举证时钟：

```text
EVIDENCE_WINDOW=PT2H
```

双方都确认“本轮举证完成”时提前封卷；否则两小时到期自动封卷。迟到材料只能作为庭审补证生成新卷宗版本。

庭审时钟与轮次：

```text
HEARING_WINDOW=PT3H
MAX_HEARING_ROUNDS=3
```

补证不得重置三小时时钟。三小时到期、三轮耗尽、事实充分或双方确认同一和解版本时，Workflow 必须强制生成非最终裁决草案并继续后续链路。

### 16.4 证据共享与可信度

双方共享证据目录，但原件按 `PARTIES / PRIVATE / PLATFORM` 分级。证据书记官输出：

```text
VERIFIED
PLAUSIBLE
SUSPICIOUS
REJECTED
NEEDS_HUMAN_REVIEW
```

AI 不得宣称无法被确定性来源证明的材料“绝对真实”。被拒绝或隔离的材料保留审计记录，不进入冻结卷宗。

### 16.5 双确认和解与按需评议

聊天中的一致意见不直接构成和解。系统创建 `settlement_proposal` 版本，由用户和商家分别确认；新版本使旧确认失效。

AI 评审团仅在高风险、未达成一致、低置信度、重大证据冲突、规则不确定或策略要求时介入。跳过评审团不能跳过平台人审。

### 16.6 实时事件与传票信箱

所有房间消息、时钟、确认和状态变化写入不可变案件事件。前端通过 SSE 接收，使用 `Last-Event-ID` 续传并按角色过滤。

传票信箱是平台内通知中心，不接入短信供应商。通知通过事务 Outbox 生成，覆盖受理、传票、举证、开庭、补证、和解、终审和执行结果。

### 16.7 新增工程模块

```text
casecore：来源、导入、总览投影、参与方；
room：房间、消息、时钟、SSE 事件；
notification：传票信箱与 Outbox；
evidence：可信度与双方完成状态；
hearing：轮次、和解与停止原因；
workflow：Temporal 时钟、Signal 与强制收敛。
```

现有 Agent Harness、六类 Agent、五条 Workflow、ReviewPacket、Approval Policy、Human Review 和 Tool Executor 继续复用。

