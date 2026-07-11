# AI 小法庭最终产品方案

## 1. 方案目标

把当前已经可运行的庭审房间，升级为一个可稳定审理、可追溯、可解释、可审核的 AI Native 履约争端法庭系统。

当前系统已经具备法庭房间的基础骨架：

- 开庭装卷：读取接待室案情卷宗和证据室证据卷宗。
- 庭审展示：展示案情接待官、证据书记官、AI 法官、AI 评审团、用户、商家消息。
- 双方陈述：用户和商家可以提交庭审陈述并入卷。
- 庭审补证：庭审阶段可以补充证据，并绑定庭审语境。
- 证据栏：支持双方视角下的我方/对方证据展示。
- 状态台：已有三轮状态、提交状态、庭审完成入口、结果页跳转。
- 可追溯基础：房间消息、轮次、部分证据引用和裁决草案已经能入库。

但最终产品不能只是“带法庭 UI 的聊天房间”。目标是让系统具备四个核心器官：

1. 法官大脑：基于案情事实地图、证据证明矩阵和双方陈述进行结构化审理。
2. 证据矩阵：让每一份证据都能映射到争议事实、证明方向、真实性、关联性和缺口。
3. 三轮审理状态机：所有流转都由后端控制，前端只消费状态合同。
4. 裁决解释链：裁决草案必须解释事实认定、证据采信、规则适用、风险和后续确认点。

本方案按照十个产品缺口展开，给出最终形态、系统职责、数据模型、接口、状态机、Agent 合同、验收清单和阶段推进顺序。

### 本轮范围说明

本轮只设计和实现“正常庭审主链路”。不把独立的“提出一致方案 / 和解方案 / 双方协商一致处理”分支放入本轮。

原因：

- 正常庭审是裁判式流程，核心是法官围绕事实、证据和规则组织三轮陈述，并生成裁决草案。
- 一致方案属于调解/和解分支，核心是双方共同让渡、方案协商、撤回或确认协议，需要独立状态、独立权限、独立文案和独立后果。
- 如果把独立一致方案混入正常庭审，会把“双方对法官拟处理方案的确认”误解为“双方自行达成和解协议”，也会干扰法官裁决草案的责任边界。

因此，本文所有“三轮庭审”均指正常审理流程，不包含和解/一致方案提议。第 3 轮保留为“拟处理方案确认”：法官提出非最终拟处理方向，双方确认或说明异议；如果双方都确认，裁决草案打上“双方一致”标签；如果任一方不确认，异议理由和待补信息进入后续审核流。后续可以单独设计 `SettlementProposal` 或 `MutualResolutionRoom` 模块，但不能和本轮主链路混用。

---

## 2. 产品定位

AI 小法庭不是普通客服聊天，也不是一次性 LLM 问答。

它应当是一个结构化审理系统：

```text
接待室事实地图
  + 证据室证明矩阵
  + 庭审三轮双方陈述
  + 庭审补证与状态流转
  -> AI 法官结构化审理
  -> AI 评审团风险复核
  -> 裁决草案与解释链
  -> 后续确认/平台审核/执行
```

核心原则：

- Java 后端是唯一状态源：轮次、权限、封存、完成、草案、审核入口都以后端为准。
- Python Agent 负责推理，不直接改业务状态。
- 前端只展示后端状态合同，不自行推断庭审阶段。
- 所有重要动作可追溯：开庭宣读、陈述、补证、封存、法官问题、裁决草案、评审团意见都要入库。
- 用户侧结果页必须明确区分“AI 裁决草案”和“最终确认结果”，不能误导为已生效裁决。

---

## 3. 总体架构

```mermaid
flowchart LR
  Intake["接待室<br/>案情事实地图"] --> Bootstrap["HearingCourtBootstrapService<br/>开庭装卷"]
  Evidence["证据室<br/>证据证明矩阵"] --> Bootstrap
  Bootstrap --> HearingState["Java Hearing State Machine<br/>三轮状态机"]
  HearingState --> Judge["Python Presiding Judge Agent<br/>法官审理"]
  Judge --> HearingState
  HearingState --> Jury["Python Jury Review Agent<br/>评审团复核"]
  Jury --> HearingState
  HearingState --> Outcome["Outcome / Review<br/>草案、解释链、后续确认"]
  HearingState --> Ledger["Hearing Ledger<br/>庭审卷轴/审理档案"]
  Frontend["Vue Hearing Court UI"] <--> HearingState
```

### 3.1 Java 后端职责

- 案件状态、房间状态、轮次状态、证据状态的唯一事实源。
- session / role / permission 校验。
- 开庭装卷、庭审卷轴、消息入库、事件广播。
- 双方陈述提交、补证提交、超时封存、轮次推进。
- 调用 Python 法官/评审团 Agent，并校验结构化输出。
- 生成和保存裁决草案、评审团复核结果、后续确认状态。
- 暴露前端状态合同和可追溯卷轴。

### 3.2 Python Agent 职责

- 接待官：产出案情事实地图。
- 证据书记官：产出证据证明矩阵。
- AI 法官：基于装卷上下文和庭审陈述输出每轮问题、轮次小结、裁决草案。
- AI 评审团：只在裁决草案阶段做风险、一致性、遗漏证据和偏听偏信复核。

Python 不直接写业务库，不直接推进案件状态，不直接让退款/补发/关闭售后生效。

### 3.3 Vue 前端职责

- 展示法庭房间、状态台、消息流、双方证据栏、输入台、卷轴、结果页。
- 所有按钮可用性以后端状态字段为准。
- 通过 SSE/轮询刷新状态。
- 不在前端伪造轮次推进、封存、裁决完成状态。

### 3.4 当前锁定决策 / Decision Register

本节作为后续开发的统一事实源。后续如果方案变化，优先更新本节，再更新实现计划和代码。

#### D1 Outcome 页面边界

`/outcome` 作为“裁决草案 / 最终结果”一体页，不再新增独立 `/draft` 路由。

- 用户、商家、审核员都可以查看 AI 裁决草案。
- 用户和商家只能看到草案、解释员复盘、后续确认状态和当事人可见摘要。
- 审核员在同一页面看到更进一步的审核操作台，可以确认或修改草案，并可查看用户不可见的完整复核信息、审核关注点和操作控件。
- 审核员确认或修改完成后，用户和商家再次进入 `/outcome` 时看到的是“最终裁决”，不再是“AI 裁决草案”。
- 页面标题、状态文案和可见模块必须由后端状态驱动，不能由前端猜测。

#### D2 审核确认与执行专员助手

审核员确认或修改裁决草案后，系统形成最终裁决，但不自动调用真实退款、补发、驳回、取消订单等下游服务。

- 最终裁决生成后，前端展示“裁决已确认”。
- 随后展示“方案已移交给执行专员助手处理”。
- 前端执行状态模块进入 3 秒加载态，可用转圈动画或状态流程图呈现“执行专员助手处理中”。
- 3 秒后前端展示“方案执行成功”。
- 当前阶段这是前端假处理，不产生真实下游执行动作。
- 文案不得宣称“真实退款已到账”“真实补发已出库”等外部业务事实，只能表达“方案执行成功 / 模拟执行链路完成 / 已移交执行专员助手处理”。
- 后续接入 Plan-and-Execute Agent 时，用真实执行事件流替换该前端假处理。

#### D3 MCP 与工具边界

MCP 只用于业务执行工具，不承载法官、接待官、证据官、评审团的内部认知工具。

- Java 业务层持有 `ToolRegistry`、`ToolAdapter`、`ToolExecutorService`，是唯一可信业务工具执行层。
- Java 后续可以把 `ToolRegistry.definitions()` 包装为 MCP Server，向未来执行 Agent 暴露业务工具 describe、schema、风险等级和调用入口。
- Python Harness 作为 MCP Client 只能读取业务工具 describe、生成动作建议、观测执行事件和做 trace / memory / evaluation。
- Python Harness 中的接待官、证据书记官、法官、评审团工具属于本地认知工具，用于案情抽取、证据矩阵生成、法官审理和评审团复核，不通过 MCP 调用，也不产生业务副作用。
- 法官不通过 MCP 直接调用 REFUND、RESHIP、REJECT_AFTER_SALE 等业务工具，只输出裁决草案、主处理方向和执行方案建议。
- 未来 Plan-and-Execute Agent 若需要执行业务工具，必须通过 Java MCP Server / Java execution endpoint，由 Java 完成权限、审核状态、幂等、审计和执行记录。

#### D4 裁决方案与执行方案粒度

v1 不拆具体执行工具链路，也不允许审核员或 Agent 自由编排多个底层工具动作。

- 裁决草案和审核确认只生成一个主处理方向，例如退款、补发、换货/维修、赔付、驳回、取消订单、人工复核等。
- 同时生成围绕该主处理方向的自然语言/结构化执行方案，说明金额、商品、时效、前置条件、风险和注意事项。
- 当前不把“退款 + 通知用户 + 通知商家 + 审计记录”硬编码成固定动作链，也不在草案阶段拆成多个工具调用。
- 后续由执行专员助手或 Plan-and-Execute Agent 读取执行方案，再根据 Java MCP business tool catalog 拆分具体业务工具调用。
- 当前前端只展示“方案已移交给执行专员助手处理”，并用 3 秒假执行状态模拟闭环。

#### D5 评审团 A2A 展示边界

AI 评审团不是二次裁决主体，只是风险复核和视野补充主体。

- 第 1/2 轮评审团静默观察 note 只入库，不展示给用户和商家。
- 第 3 轮后正式复核 report 注入法官上下文，用于最终裁决草案生成或修订。
- 用户和商家只能看到经过中文化的复核摘要，不看到完整结构化字段和内部风险策略。
- 审核员可以看到完整复核报告、风险等级、可信分、修改建议、证据引用和结构化字段。
- 原始 A2A JSON 只供系统内部、审计链路和开发排查使用，不直接暴露给普通前端。

#### D6 第二轮补证与证据矩阵版本

第二轮是证据解释与补证重点轮。补证立即共享展示，但正式证据矩阵不频繁重建。

- 第二轮期间允许双方补证。
- 补证提交后立即对另一方可见，并进入庭审卷轴。
- 补证上传本身不立即重建正式 active evidence dossier。
- 第二轮封存后，证据书记官统一复核本轮全部补证和双方证据解释。
- 复核完成后生成新的 active evidence_dossier v2。
- 第三轮法官拟处理方向和最终裁决草案必须读取 v2。
- 庭审卷轴记录“证据矩阵从 v1 更新到 v2”的摘要。
- 如果 v2 生成失败，必须进入异常/人工关注状态，不得静默继续使用旧 v1 伪装成最新案卷。

#### D7 第三轮拟处理方向意见轮

第三轮不是“和解/一致方案”主线，也不是当事人直接改判按钮，而是围绕法官拟处理方向发表自然语言意见。

- 第二轮后，法官给出非最终拟处理方向，例如倾向退款、倾向补发、倾向驳回或倾向人工复核。
- 用户和商家在第三轮用自然语言表达认可、异议、补充理由或对拟处理方向的担忧。
- 系统不直接采纳当事人第三轮自然语言评价，也不把当事人意见直接转换成最终裁决。
- 第三轮自然语言意见作为 `review_focus_signal` 进入评审团复核范围。
- 第三轮自然语言意见只作为陪审复核 focus；评审/证据矩阵更新属于 A2A 上下文输入，不是直接裁决依据。
- 评审团结合 intake_dossier、active evidence_dossier v2、三轮双方陈述、法官拟处理方向和 `review_focus_signal`，检查是否存在遗漏事实、遗漏证据、偏听偏信、规则适用不一致或需要审核员关注的漏洞。
- 评审团输出 `jury_review_report` 给法官。
- 法官读取 `jury_review_report` 后重新生成或修订 AI 裁决草案。
- 草案生成后进入 `/outcome` 草案流程，由审核员确认或修改。

### 3.5 当前仍待深化问题 / Open Questions

- Java MCP Server 的协议形态与鉴权方式：优先围绕业务工具 catalog / call 设计，不覆盖 Python 认知工具。
- 执行专员助手的真实实现：当前为前端 3 秒假处理，未来替换为 Plan-and-Execute Agent + Java MCP business tools。
- 审核员完整复核报告的 UI：需要在不暴露原始 A2A JSON 的前提下，展示完整报告、风险等级、可信分和证据引用。

### 3.6 当前执行任务拆分 / Implementation Task Register

本节是 D1-D7 决策进入开发时的执行登记表。执行规则：每完成一项任务，必须完成对应验证、提交并推送一次，再进入下一项；十项全部完成后，按任务逐项复核，发现缺项必须补齐。

#### Task 1：统一更新总方案文档

目标：把 Outcome、MCP、第三轮、执行专员助手、陪审团 A2A、证据矩阵 v2 等已锁定决策沉淀到本总方案文件中。

验收标准：

- 本文件包含 D1-D7 决策登记。
- 本文件包含当前十项执行任务登记。
- 后续开发不得新增分散方案文件；如方案变化，优先更新本文件。

#### Task 2：后端 Review 确认链路改造

目标：审核员确认裁决后只形成最终裁决，不自动调用真实或模拟业务执行工具。

验收标准：

- `ReviewApplicationService` / `PostReviewOrchestrationService` 不在确认裁决时自动触发 `ToolExecutorService.executeApprovedActions(...)`。
- 创建最终裁决后，`/outcome` 能读取最终态。
- 回归测试证明“确认裁决 ≠ 执行退款/补发/赔付/驳回等工具”。

#### Task 3：Outcome 执行专员助手假处理

目标：最终裁决确认后，前端展示执行专员助手接手的模拟状态闭环。

验收标准：

- 页面展示“裁决已确认”。
- 页面展示“方案已移交给执行专员助手处理”。
- 执行状态进入 3 秒加载态。
- 3 秒后展示“方案执行成功”。
- 文案不宣称真实退款到账、真实补发出库、真实赔付完成等外部业务事实。

#### Task 4：Outcome 审核员操作台收敛

目标：审核员操作台从底层动作编排收敛为“主处理方向 + 执行方案说明/裁决文本修改”。

验收标准：

- 审核员仍可确认或修改裁决草案。
- 审核员不再需要维护多个底层 action row。
- 页面保留未来执行 Agent 可读取的主处理方向和执行方案文本。
- 用户和商家看不到审核员内部操作台。

#### Task 5：ToolRegistry / MCP 边界固化

目标：把 Java 业务工具层和 Python Harness 认知工具层边界落实到代码与测试。

验收标准：

- Java 业务层继续持有 `ToolRegistry`、`ToolAdapter`、`ToolExecutorService`。
- Python 法官、接待官、证据书记官、评审团不直接调用业务工具。
- 业务工具 describe / call 的未来 MCP 包装以 Java 为权威入口。
- Harness 只能观测工具 catalog / execution event，不能绕过 Java 权限和审计。

#### Task 6：第三轮拟处理方向意见轮语义

目标：第三轮改为双方围绕法官拟处理方向发表自然语言意见，而不是一致方案或直接改判。

验收标准：

- 第二轮后，法官生成非最终拟处理方向。
- 第三轮双方输入自然语言认可、异议、补充理由或担忧。
- 第三轮自然语言沉淀为 `review_focus_signal`。
- 系统不直接把第三轮意见采纳为最终裁决。

#### Task 7：陪审团 A2A 复核链路

目标：陪审团成为第三轮后正式复核节点，通过 A2A 把复核意见传给法官。

验收标准：

- 第 1/2 轮陪审团静默观察 note 只入库，不展示给普通用户。
- 第 3 轮后生成正式 `jury_review_report`。
- `jury_review_report` 注入法官最终草案上下文。
- 用户/商家只看到中文摘要；审核员可见完整复核报告；原始 A2A JSON 只供内部审计。

#### Task 8：第二轮补证后证据矩阵 v2

目标：第二轮补证封存后，证据书记官统一复核并更新 active evidence dossier。

验收标准：

- 第二轮补证立即对对方可见并进入庭审卷轴。
- 第二轮封存后生成 `evidence_dossier_v2`。
- 第三轮和最终裁决草案必须读取 v2。
- v2 生成失败时进入异常/人工关注状态，不静默回退到 v1。

#### Task 9：庭审卷轴增强

目标：庭审卷轴从聊天记录升级为审理档案。

验收标准：

- 卷轴记录开庭装卷、案情宣读、证据宣读、每轮法官问题、双方陈述、补证、证据矩阵版本变化、陪审团观察/复核、法官拟处理方向、裁决草案、审核员确认/修改、执行专员助手状态。
- 卷轴信息可支撑平台审核、申诉复核、审计和裁决解释。

#### Task 10：端到端复核与缺项补全

目标：十项任务完成后按总方案逐项复核，补齐缺项，直到链路满足当前最终法庭产品设想。

验收标准：

- 回归验证覆盖 Review、Outcome、Hearing、Evidence、Intake、ToolRegistry/MCP 边界。
- 浏览器端验证用户/商家/审核员视角关键路径。
- 复核表逐项标明证据来源；任何缺项必须进入补全任务，不得以“测试通过”替代需求完成。

---

## 4. 十点详细方案

## 4.1 法官核心智能

### 当前问题

法官已经能出现在庭审页中，能开场、提问、展示案情和证据，但需要确认每轮审理是否真正由 LLM + Harness Context Pack 生成，而不是模板/规则拼接。

最终产品中，法官必须是“结构化审理 Agent”，而不是普通聊天回复。

### 最终形态

AI 法官每轮输入：

```json
{
  "case_id": "CASE_xxx",
  "round_no": 1,
  "round_stage": "FACT_STATEMENT",
  "actor_scope": "COURTROOM_SHARED",
  "intake_dossier": {},
  "evidence_dossier": {},
  "current_round_party_messages": [],
  "supplemental_evidence_events": [],
  "previous_round_summaries": [],
  "hearing_ledger_digest": {},
  "judge_policy": {
    "non_final_notice": true,
    "third_person_narration": true,
    "do_not_leak_internal_codes": true
  }
}
```

AI 法官每轮输出：

```json
{
  "room_utterance": "法官展示在庭审聊天区的话",
  "round_summary": {
    "round_no": 1,
    "stage": "FACT_STATEMENT",
    "user_position_summary": "用户本轮陈述摘要",
    "merchant_position_summary": "商家本轮陈述摘要",
    "accepted_points": [],
    "contested_points": [],
    "missing_points": []
  },
  "next_round_question": {
    "target_stage": "EVIDENCE_EXPLANATION",
    "question_for_user": "请用户解释未收到包裹与现有证据的关系。",
    "question_for_merchant": "请商家说明签收凭证来源、签收人身份和物流交接记录。"
  },
  "judge_attention": [
    "签收人身份仍不明确",
    "物流签收记录只能证明物流系统状态，不能单独证明用户本人实际收到"
  ],
  "status_signal": "NEXT_ROUND_READY",
  "confidence": 0.78
}
```

第三轮结束后，AI 法官输出裁决草案：

```json
{
  "draft_text": "AI 法官裁决草案正文",
  "recommended_decision": "RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW",
  "fact_findings": [],
  "evidence_assessment": [],
  "policy_application": [],
  "reviewer_attention": [],
  "risk_flags": [],
  "confidence": 0.76
}
```

### Harness Context Pack 组成

法官上下文必须按 section 精准拼接：

| Section | 内容 | 优先级 |
|---|---|---:|
| trusted_runtime_context | case_id、room、actor、session、权限、agent_key | 100 |
| node_task_prompt | 当前轮任务：事实陈述/证据解释/方案确认/裁决草案 | 98 |
| intake_dossier | 接待室事实地图 | 95 |
| evidence_dossier_ref | 证据矩阵版本引用：baseline_version、active_version、updated_after_round | 96 |
| evidence_dossier | 当前有效证据证明矩阵，必须读取 active_version | 95 |
| current_round_party_messages | 本轮双方陈述 | 90 |
| supplemental_evidence_events | 庭审补证记录 | 85 |
| previous_round_summaries | 前序轮次小结 | 80 |
| jury_a2a_notes | 陪审团通过 A2A 传给法官的静默复核/正式复核消息 | 78 |
| hearing_ledger_digest | 庭审卷轴压缩摘要 | 75 |
| output_contract | JSON 输出合同 | 100 |

### 验收标准

- 每轮法官调用都有 `agent_run_id`、prompt profile、输入 context hash、输出 JSON。
- 法官输出通过 schema 校验后才入库。
- 模型失败时只允许进入明确 fallback，不得把 HTTP/JSON 错误暴露给用户。
- 法官消息必须第三人称、中文化、不可泄露后端枚举。

---

## 4.2 三轮状态机

### 当前问题

页面能显示第 1/2/3 轮，双方能提交陈述，但最终法庭需要更硬的后端状态机：

- 双方都提交后是否自动封存。
- 超时是否自动封存。
- 法官处理中是否可重复触发。
- 刷新、断线、重复提交后状态是否一致。
- 前端是否完全不能越权推进轮次。

### 最终状态枚举

建议后端区分“庭审总阶段”和“当前轮次阶段”。

#### Hearing phase

```text
HEARING_NOT_OPENED
ROUND_OPEN
WAITING_COUNTERPARTY
ROUND_SEALING
JUDGE_PROCESSING
NEXT_ROUND_READY
JUDGE_DRAFTING
JURY_REVIEWING
DRAFT_READY
WAITING_HUMAN_REVIEW
FINAL_DECISION_READY
```

#### Round stage

```text
FACT_STATEMENT
EVIDENCE_EXPLANATION
REMEDY_CONFIRMATION
```

三轮含义：

| 轮次 | stage | 中文名 | 目标 | 明确不做 |
|---|---|---|---|---|
| 第 1 轮 | FACT_STATEMENT | 事实陈述 | 双方说明事实经过、争议点、各自主张 | 不要求双方谈和解 |
| 第 2 轮 | EVIDENCE_EXPLANATION | 证据解释 | 双方解释证据来源、形成时间、真实性、与争议事实的关系 | 不要求双方提出共同方案 |
| 第 3 轮 | REMEDY_CONFIRMATION | 方案确认 | 法官基于前两轮提出非最终拟处理方向，双方分别确认或说明异议 | 不等同于“一致方案”或“和解协议” |

第 3 轮的“方案确认”不是双方自行提出和解方案，而是对法官拟处理方向的确认/异议。双方都确认时，后续裁决草案增加 `party_alignment=PARTIES_ALIGNED` 和“双方一致”展示标签；任一方不确认时，后续裁决草案增加 `party_alignment=DISPUTED`，并携带 `party_disagreement_summary`、`missing_information_for_review`、`reviewer_attention`。该标签只表示双方对法官拟处理方向一致，不代表平台最终结果已经生效。

#### Round status

```text
NOT_STARTED
OPEN
WAITING_COUNTERPARTY
SEALED_BY_SUBMISSION
SEALED_BY_TIMEOUT
JUDGE_PROCESSING
SUMMARY_READY
```

### 状态流转

```mermaid
stateDiagram-v2
  [*] --> ROUND_OPEN: 开庭并进入第1轮
  ROUND_OPEN --> WAITING_COUNTERPARTY: 一方提交
  WAITING_COUNTERPARTY --> ROUND_SEALING: 双方提交
  ROUND_OPEN --> ROUND_SEALING: 超时自动封存
  WAITING_COUNTERPARTY --> ROUND_SEALING: 超时自动封存
  ROUND_SEALING --> JUDGE_PROCESSING: 触发法官本轮处理
  JUDGE_PROCESSING --> NEXT_ROUND_READY: 第1/2轮生成下一轮问题
  NEXT_ROUND_READY --> ROUND_OPEN: 打开下一轮
  JUDGE_PROCESSING --> JUDGE_DRAFTING: 第3轮结束
  JUDGE_DRAFTING --> JURY_REVIEWING: 裁决草案生成
  JURY_REVIEWING --> DRAFT_READY: 评审团通过/给出建议
  DRAFT_READY --> WAITING_HUMAN_REVIEW: 等待后续确认
  WAITING_HUMAN_REVIEW --> FINAL_DECISION_READY: 最终确认完成
```

### 后端状态合同

`GET /api/disputes/{caseId}/hearing` 应返回：

```json
{
  "status": {
    "hearing_phase": "ROUND_OPEN",
    "round_no": 1,
    "round_stage": "FACT_STATEMENT",
    "round_status": "OPEN",
    "user_submission_status": "NOT_SUBMITTED",
    "merchant_submission_status": "SUBMITTED",
    "deadline_at": "2026-07-08T23:59:00+08:00",
    "sealed_at": null,
    "judge_processing": false,
    "jury_reviewing": false,
    "can_submit_statement": true,
    "can_supplement_evidence": true,
    "can_complete_hearing": false,
    "latest_draft_id": null,
    "next_step_hint": "等待用户提交本轮陈述，倒计时结束后会自动封存。"
  }
}
```

### 验收标准

- 双方提交后，本轮一定封存。
- 超时后，本轮一定封存，并记录 `SEALED_BY_TIMEOUT`。
- 同一 actor 同一轮重复提交不产生多份有效陈述，保留审计事件。
- 前端刷新后状态与刷新前一致。
- “庭审完成”只有在 `can_complete_hearing=true` 时可用。
- 所有状态推进接口幂等。

---

## 4.3 证据书记官与证据证明矩阵

### 当前问题

证据上传、提交、展示和补证已经有基础，但法官最终需要看的不是文件列表，而是“事实-证据证明关系”。

### 最终 evidence_dossier

```json
{
  "dossier_version": 3,
  "evidence_items": [
    {
      "evidence_id": "EVD_USER_001",
      "party_role": "USER",
      "file_name": "物流客服沟通截图.jpg",
      "evidence_type": "IMAGE",
      "parsed_text": "OCR 摘要",
      "claimed_fact": "用户称快递员未联系本人",
      "supports_fact_ids": ["FACT_NOT_RECEIVED"],
      "opposes_fact_ids": ["FACT_SIGNED_BY_USER"],
      "authenticity_score": 0.82,
      "relevance_score": 0.91,
      "completeness_score": 0.66,
      "verification_status": "PARTIALLY_VERIFIED",
      "risk_flags": ["截图时间需核验"],
      "visibility": "COURTROOM_SHARED"
    }
  ],
  "fact_evidence_matrix": [
    {
      "fact_id": "FACT_SIGNED",
      "fact": "物流系统显示包裹已签收",
      "supporting_evidence": ["EVD_MERCHANT_001"],
      "opposing_evidence": ["EVD_USER_001"],
      "evidence_strength": "MEDIUM",
      "judge_attention": "需要核验签收人身份、签收位置和物流投递照片"
    }
  ],
  "party_evidence_summary": {
    "USER": {
      "strong_points": [],
      "weak_points": [],
      "missing_items": []
    },
    "MERCHANT": {
      "strong_points": [],
      "weak_points": [],
      "missing_items": []
    }
  },
  "verified_facts": [],
  "contested_facts": [],
  "evidence_gaps": [],
  "authenticity_flags": [],
  "overall_confidence_score": 76,
  "handoff_notes": "证据可以支持物流已签收，但不足以直接证明用户本人实际收到。"
}
```

### 证据书记官职责

- 不重新裁判案件。
- 不站队。
- 只围绕证据真实性、完整性、关联性、形成时间、来源链路提问。
- 将上传材料映射到争议事实。
- 标记证据缺口和法官注意事项。

### Java–Python Harness 上下文边界

证据书记官调用统一使用版本化的 `EvidenceContextEnvelope`。Java 不直接编写面向模型的案情解释、核验重点或 Prompt 文案，只交付经过权限过滤且满足合同的数据包；Python Harness 再基于该数据包构造语义 Context Pack。

```json
{
  "context_envelope": {
    "schema_version": "evidence_context_envelope.v1",
    "captured_at": "2026-07-11T10:00:00Z",
    "case_snapshot": {
      "case_id": "CASE_xxx",
      "case_version": 3,
      "case_status": "EVIDENCE_OPEN",
      "case_type": "AFTER_SALE_DISPUTE",
      "dispute_type": "SIGNED_NOT_RECEIVED",
      "initiator_role": "USER",
      "title": "案件标题",
      "description": "案件原始描述",
      "risk_level": "MEDIUM",
      "route_type": "FULL_HEARING",
      "order_id": "ORDER_xxx",
      "after_sale_id": null,
      "logistics_id": "LOGISTICS_xxx",
      "source_type": "INTAKE_CREATED",
      "source_system": null,
      "external_case_ref": null,
      "current_room": "EVIDENCE",
      "current_deadline_at": null
    },
    "intake_dossier_snapshot": {
      "dossier_id": "DOSSIER_xxx",
      "schema_version": "intake_case_detail.v1",
      "dossier_version": 3,
      "source_turn_no": 5,
      "quality_score": 82,
      "ready_for_next_step": true,
      "admission_recommendation": "ACCEPTED",
      "updated_at": "2026-07-11T09:55:00Z",
      "payload": {}
    },
    "actor_snapshot": {
      "actor_id": "user-local",
      "actor_role": "USER",
      "initiator_role": "USER",
      "access_session_id": "ACCESS_xxx",
      "agent_session_id": "AGENT_SESSION_xxx",
      "conversation_scope": "CASE_xxx:EVIDENCE:USER:user-local",
      "prompt_profile_id": "EVIDENCE_CLERK:USER:v1",
      "memory_policy_id": "MEMEO_DEFAULT"
    },
    "current_event": {
      "event_id": "MESSAGE_xxx",
      "event_type": "PARTY_MESSAGE",
      "message_type": "PARTY_TEXT",
      "actor_id": "user-local",
      "actor_role": "USER",
      "text": "当事人本轮原始输入",
      "attachment_refs": [],
      "turn_no": 2,
      "occurred_at": "2026-07-11T10:00:00Z"
    },
    "visible_evidence": [],
    "private_conversation": {
      "agent_session_id": "AGENT_SESSION_xxx",
      "conversation_scope": "CASE_xxx:EVIDENCE:USER:user-local",
      "source_count": 0,
      "truncated": false,
      "recent_turns": []
    },
    "room_policy": {
      "room_id": "ROOM_xxx",
      "room_type": "EVIDENCE",
      "room_status": "OPEN",
      "current_deadline_at": null,
      "initiator_role": "USER",
      "initiator_evidence_required": true
    }
  },
  "agent_context": {
    "tenant_id": "default",
    "case_id": "CASE_xxx",
    "room_type": "EVIDENCE",
    "actor_id": "user-local",
    "actor_role": "USER",
    "access_session_id": "ACCESS_xxx",
    "permission_level": "PARTY_USER",
    "permission_scopes": [
      "CASE_READ",
      "ROOM_MESSAGE_READ",
      "ROOM_MESSAGE_WRITE",
      "EVIDENCE_READ",
      "EVIDENCE_SUBMIT",
      "EVIDENCE_PRIVATE_READ",
      "AGENT_SESSION_READ",
      "AGENT_SESSION_WRITE"
    ],
    "agent_key": "EVIDENCE_CLERK",
    "agent_invocation_id": "AGENT_INVOCATION_xxx",
    "agent_session_id": "AGENT_SESSION_xxx",
    "conversation_scope": "CASE_xxx:EVIDENCE:USER:user-local",
    "scope_type": "EVIDENCE_PARTY_PRIVATE",
    "allowed_actor_ids": ["user-local"],
    "allowed_actor_roles": ["USER"],
    "prompt_profile_id": "EVIDENCE_CLERK:USER:v1",
    "memory_policy_id": "MEMEO_DEFAULT"
  }
}
```

Java 业务层负责：

- 校验 actor/session 权限，并按证据可见性过滤 `visible_evidence`。
- 提供案件主表原始字段、当前有效案情卷宗及版本、证据原始解析结果和当前事件。
- 所有时间字段在 envelope 中固定为 ISO-8601 字符串，不能依赖 Jackson 的时间戳数组或数字序列化配置。
- `private_conversation` 只读取与当前 `agent_session_id` 精确匹配的正式记忆；无 session 的历史记录和其他会话记录一律不进入 envelope。
- 保证幂等、事务、审计、持久化以及房间和案件状态推进。
- 通过 Java 强类型 DTO 构造固定版本 envelope；完整运行时合同由 Python 严格校验。Java 不把争议类型翻译成面向 Agent 的事实结论。

Python Harness 负责：

- 通过 `EvidenceContextAssembler` 将 envelope 组装为 `current_turn`、`case_identity`、`canonical_case_dossier`、私有记忆和可见证据等 Context Pack 分区。
- 在案情卷宗缺失或字段不足时生成仅供模型推理使用的语义补充，不回写或冒充正式案情事实。
- 执行 token 预算、历史压缩、Prompt 契约和结构化输出校验；模型可见证据按“当前引用优先、其余最近材料”生成有界预览，原始 hash、owner id、content URL、metadata 和 extraction 不直接进入 Prompt。
- 只接受 `evidence_context_envelope.v1`，不保留旧版扁平 evidence turn payload 的兼容分支；未知版本或旧结构直接返回合同校验错误。

本轮结构调整不引入 `relevance_status`、证据排除决定或新的举证完成门槛；不相关证据如何识别、如何进入矩阵以及是否阻断流程，必须在后续证据校验方案中单独定稿。

由于正式版不保留旧合同兼容层，Java 与 Python 必须原子升级；Python 返回 422 时 Java 将其作为 `AGENT_OUTPUT_SCHEMA_INVALID` 显式失败并告警，不得伪装成普通书记官降级回复。告警只记录校验字段路径、错误类型和错误描述，不记录 Pydantic 返回的原始输入内容。

### 证据矩阵版本化规则

开庭装卷时，`HearingCourtBootstrapService` 只冻结“开庭基线版本”，例如 `evidence_dossier_version=1`。这个基线用于证明开庭时法官看到过哪些证据，并写入庭审卷轴，不代表后续证据矩阵永远固定。

第 2 轮证据解释结束后，证据书记官必须执行一次“二轮证据复核”：

1. 读取开庭基线证据矩阵、双方第 2 轮证据解释、庭审补证事件。
2. 更新 `fact_evidence_matrix`、证据评分、证据缺口、法官注意事项。
3. 生成新的 `evidence_dossier_version`，例如从 v1 更新为 v2。
4. 写入卷轴事件 `EVIDENCE_DOSSIER_REVISED`，记录旧版本、新版本、变更摘要、触发轮次。

法官每次调用 Harness Context Pack 时不能复用过期固定证据，而必须读取“当前有效证据矩阵版本”：

```json
{
  "evidence_dossier_ref": {
    "baseline_version": 1,
    "active_version": 2,
    "updated_by": "EVIDENCE_CLERK",
    "updated_after_round": 2,
    "revision_reason": "双方完成证据解释并补充物流沟通记录"
  },
  "evidence_dossier": {}
}
```

因此，法官第 3 轮方案确认和裁决草案必须基于最新 `active_version`。如果最新矩阵生成失败，后端只能进入明确 fallback：提示证据复核暂未完成，不能让法官使用旧矩阵继续生成裁决草案。

### 验收标准

- 每份正式提交证据都有评分：真实性、关联性、完整性。
- 每个高优先级争议事实都能看到支持/反驳/缺口。
- 法官上下文里必须包含最新版本的 `fact_evidence_matrix` 和 `evidence_dossier_ref`。
- 庭审补证会更新矩阵版本，并记录矩阵版本号。
- 第 2 轮证据书记官复核后必须生成新的 evidence_dossier 版本或写入失败原因。
- 法官第 3 轮和裁决草案不得使用已过期的开庭基线矩阵。

---

## 4.4 接待室案情事实地图

### 当前问题

如果接待室输出弱案情，庭审就会出现“描述内容为乱码，无法识别具体问题”这类输入。最终产品里，接待室必须稳定沉淀事实地图，而不是聊天摘要。

### 最终 intake_dossier

```json
{
  "dossier_version": 5,
  "case_story": "物流记录显示包裹已签收，但用户称本人未实际收到商品，并要求核验签收记录后处理退款或补发。",
  "initiator_role": "USER",
  "respondent_role": "MERCHANT",
  "claim": {
    "requested_resolution": "退款或补发",
    "amount": 299,
    "non_monetary_request": "核验签收记录"
  },
  "timeline": [
    {
      "time": "2026-07-01",
      "event": "订单发货",
      "source": "外部导入",
      "confidence": 0.8
    }
  ],
  "known_facts": [
    {
      "fact_id": "FACT_LOGISTICS_SIGNED",
      "fact": "物流系统显示包裹已签收",
      "source": "物流记录",
      "support_level": "SUPPORTED"
    }
  ],
  "disputed_facts": [
    {
      "fact_id": "FACT_USER_RECEIVED",
      "fact": "用户是否实际收到商品",
      "user_position": "用户称本人未收到包裹",
      "merchant_position": "商家称物流记录显示已签收",
      "importance": "HIGH"
    }
  ],
  "missing_information": [
    "签收人身份",
    "签收位置",
    "物流投递照片"
  ],
  "platform_policy_hooks": [
    "签收争议",
    "举证责任",
    "物流异常核验"
  ],
  "risk_level": "MEDIUM",
  "quality_score": 82,
  "handoff_notes": "庭审应重点核验签收真实性和物流交接链路。"
}
```

### 接待官输出要求

- 平台整理内容必须第三人称。
- 原始陈述可保留第一人称，但必须放在 raw/original 字段。
- 内部枚举和字段名不得直接进入用户可见文本。
- 质量分未达阈值时，不允许进入庭审，只能继续追问。

### 验收标准

- `quality_score >= threshold` 后才能提交到证据室或庭审。
- `case_story`、`known_facts`、`disputed_facts`、`missing_information` 不为空。
- 乱码/低置信案情进入庭审前必须 fallback 为“案情不足，需补充信息”，不能伪造事实。

---

## 4.5 AI 评审团复核节点与 A2A 通信

### 当前问题

UI 有 AI 评审团角色，但最终产品里评审团应成为“合议复核”节点，而不是第二个裁决主体。陪审团的价值是通过 A2A（Agent-to-Agent）链路把风险、遗漏和修改建议传给法官，让法官基于复核意见生成或修订裁决草案。

### 最终流程

```text
第 1 轮事实陈述结束
  -> 陪审团静默旁听事实完整性，不对外发言
  -> A2A note 传给法官，作为第 2 轮追问参考

第 2 轮证据解释结束
  -> 证据书记官主责更新 evidence_dossier v2
  -> 陪审团静默复核证据风险，不替代证据书记官
  -> A2A note 传给法官，作为第 3 轮方案确认参考

第 3 轮方案确认结束
  -> 法官整理拟裁决方向和双方确认/异议
  -> 陪审团正式复核
  -> 输出 jury_review_report
  -> A2A report 传回法官
  -> 法官生成或修订 AI 裁决草案
  -> Java 保存草案、jury_review、A2A trace
  -> 进入后续确认/审核流
```

评审团不直接生成裁决草案给审核员。裁决草案的唯一生成主体是主审法官；评审团是合议复核和风险建议主体。

### 评审团输入

```json
{
  "judge_proposed_remedy": {},
  "party_alignment": {
    "user_confirmation": "CONFIRMED",
    "merchant_confirmation": "DISPUTED",
    "disagreement_summary": "商家认为物流签收记录已足以证明履约完成"
  },
  "intake_dossier": {},
  "evidence_dossier_ref": {
    "baseline_version": 1,
    "active_version": 2
  },
  "evidence_dossier": {},
  "round_summaries": [],
  "hearing_ledger_digest": {}
}
```

### 评审团输出

```json
{
  "a2a_message_type": "JURY_REVIEW_REPORT",
  "risk_level": "MEDIUM",
  "confidence_score": 75,
  "consistency_passed": true,
  "party_alignment_assessment": "DISPUTED",
  "bias_flags": [],
  "missing_evidence_flags": [
    "签收人身份仍需后续确认"
  ],
  "revision_suggestions": [
    "裁决草案应明确物流签收记录不能单独证明用户本人收货"
  ],
  "judge_instruction": "建议法官在裁决草案中保留商家异议，并把签收人身份列为审核关注点。",
  "requires_human_review": true
}
```

### A2A 消息契约

A2A 是本系统的亮点之一：它不是普通聊天消息，而是 agent 之间可追溯、可校验、可审计的结构化通信。建议建立统一 envelope：

```json
{
  "a2a_message_id": "A2A_001",
  "case_id": "CASE_xxx",
  "from_agent": "JURY_PANEL",
  "to_agent": "PRESIDING_JUDGE",
  "message_type": "JURY_REVIEW_REPORT",
  "round_no": 3,
  "input_refs": {
    "intake_dossier_version": 5,
    "evidence_dossier_version": 2,
    "hearing_ledger_hash": "hash",
    "judge_proposed_remedy_id": "JUDGE_REMEDY_001"
  },
  "payload": {},
  "created_at": "2026-07-09T10:00:00+08:00"
}
```

A2A 消息默认 `visibility=SYSTEM_AUDIT_ONLY` 或 `PLATFORM_ONLY`。庭审页可以展示经过中文化压缩后的陪审团消息卡，但不能暴露内部 JSON、prompt、context hash 或模型调度细节。

### 验收标准

- 第 1/2 轮允许陪审团静默复核并通过 A2A note 给法官，但不对外直接裁决。
- 第 2 轮证据复核主责属于证据书记官，陪审团只做风险旁听和遗漏检查。
- 第 3 轮结束后，裁决草案必须经过评审团正式复核。
- 评审团不直接生成裁决草案；法官读取 `JURY_REVIEW_REPORT` 后生成或修订草案。
- 评审结果入库，可被结果页和审核页读取。
- 高风险、低可信、关键证据缺失时必须标记 `requires_human_review=true`。
- A2A 消息必须记录发送方、接收方、输入版本引用、payload、时间和可见性。

---

## 4.6 庭审卷轴 / 审理档案

### 当前问题

庭审消息、补证消息和轮次已有入库基础，但最终卷轴要成为真正可审计的审理档案。

### 最终 ledger 内容

```json
{
  "case_id": "CASE_xxx",
  "ledger_version": 1,
  "opening": {
    "bootstrap_snapshot_id": "SNAPSHOT_001",
    "intake_dossier_version": 5,
    "evidence_dossier_version": 3
  },
  "rounds": [
    {
      "round_no": 1,
      "stage": "FACT_STATEMENT",
      "status": "SEALED_BY_SUBMISSION",
      "judge_question": "请双方说明签收事实。",
      "user_statement_message_id": "MSG_USER_1",
      "merchant_statement_message_id": "MSG_MERCHANT_1",
      "supplemental_evidence_refs": [],
      "judge_round_summary_id": "MSG_JUDGE_SUMMARY_1",
      "sealed_at": "2026-07-08T21:10:00+08:00",
      "timeout_sealed": false
    }
  ],
  "final_draft_id": "DRAFT_001",
  "jury_review_id": "JURY_001",
  "hash": "ledger_hash_for_audit"
}
```

### 验收标准

- 卷轴不使用 mock。
- 能展示每轮法官问题、双方陈述、补证引用、封存状态、小结、时间。
- 能支撑平台审核员复核、申诉审计、裁决解释。
- 关键入卷内容建议生成 hash，后续用于审计不可抵赖。

---

## 4.7 权限与 session

### 当前问题

用户/商家隔离和审核员全权限方向已经有基础，但最终每个功能都必须挂 session 权限校验，而不是只靠前端隐藏按钮。

### 权限模型

| 角色 | 权限 |
|---|---|
| USER | 查看自己接待室聊天、自己证据、庭审共享消息、对方已共享证据、提交自己陈述、补充自己证据 |
| MERCHANT | 查看自己接待室聊天、自己证据、庭审共享消息、对方已共享证据、提交自己陈述、补充自己证据 |
| CUSTOMER_SERVICE | 查看接待室业务必要信息，不可越权看私密证据原件 |
| PLATFORM_REVIEWER | 查看完整案卷、双方私有/共享证据、庭审卷轴、草案、评审团意见，执行最终确认 |
| AGENT | 只能读取被授权的 context section，写入自己的 agent 输出 |
| ADMIN | 系统管理和审计权限 |

### 消息可见性

```text
PRIVATE_USER
PRIVATE_MERCHANT
COURTROOM_SHARED
PLATFORM_ONLY
SYSTEM_AUDIT_ONLY
```

### 验收标准

- 所有 hearing/evidence/intake/outcome/review endpoint 都通过 `AuthenticatedActor` 校验。
- 用户不能看到商家私有接待聊天。
- 商家不能看到用户私有接待聊天。
- 双方只能看到庭审共享消息和已共享证据。
- 审核员能看到完整案卷和审计信息。
- Agent 调用必须带 `agent_session_id` 和 `conversation_scope`。

---

## 4.8 “庭审完成”后端语义

### 当前问题

“庭审完成”不能只是前端跳 outcome。它应该代表后端已经确认：

- 三轮是否完成。
- 法官是否生成裁决草案。
- 评审团是否复核。
- 是否允许当事人查看草案。
- 是否进入后续确认。

### 完成接口

`POST /api/disputes/{caseId}/hearing/complete`

返回：

```json
{
  "hearing_phase": "DRAFT_READY",
  "can_complete_hearing": true,
  "latest_draft_id": "DRAFT_001",
  "jury_review_id": "JURY_001",
  "review_gate_ready": true,
  "outcome_visible": true,
  "next_step_hint": "AI 裁决草案已生成，可查看草案并等待后续确认。"
}
```

### 规则

- 如果三轮未完成：返回当前状态，不允许跳 outcome。
- 如果法官还在生成草案：返回 `JUDGE_DRAFTING`。
- 如果评审团还在复核：返回 `JURY_REVIEWING`。
- 如果草案已准备好：允许跳 outcome。
- 如果重复点击：返回同一个状态，不重复生成草案。

### 验收标准

- 完成按钮由 `can_complete_hearing` 控制。
- 前端点击后必须等待后端成功返回，再跳 outcome。
- outcome 草案态不得展示“最终裁决/已生效”。
- 重复点击不会重复生成草案、补救计划或审核记录。

---

## 4.9 实时协作

### 当前问题

已有 SSE / room events 基础，但最终双方同时在庭审房间，需要更强的事件覆盖和断线恢复。

### 事件类型

```text
HEARING_OPENED
ROUND_OPENED
PARTY_STATEMENT_SUBMITTED
PARTY_EVIDENCE_SUPPLEMENTED
ROUND_SEALED
JUDGE_PROCESSING_STARTED
JUDGE_ROUND_SUMMARY_READY
NEXT_ROUND_READY
JUDGE_DRAFT_STARTED
JUDGE_DRAFT_READY
JURY_REVIEW_STARTED
JURY_REVIEW_READY
HEARING_PHASE_CHANGED
OUTCOME_VISIBLE
```

### 断线恢复

前端重连后：

1. 调 `GET /hearing` 获取当前完整状态。
2. 调 `GET /hearing/ledger` 获取 durable 卷轴。
3. SSE 从最新 event id 继续订阅。
4. 如果事件丢失，以后端快照为准重建页面。

### 验收标准

- 用户提交后，商家视角实时看到“用户已提交”状态。
- 商家提交后，用户视角实时看到“商家已提交”状态。
- 法官处理中、轮次封存、下一轮打开实时更新。
- 补证提交后，对方在权限允许范围内实时看到证据引用。
- 断线重连后页面状态和后端一致。

---

## 4.10 裁决解释能力

### 当前问题

结果页已经能展示 AI 裁决草案，但最终产品要能解释为什么这样裁决，而不是只给结论。

### 最终解释链

```json
{
  "recommended_decision": "RESHIP_OR_REFUND_AFTER_SIGNATURE_REVIEW",
  "plain_language_summary": "建议先核验签收凭证；若签收依据不足，倾向补发或退款。",
  "fact_findings": [
    {
      "fact": "物流系统显示包裹已签收",
      "finding": "该事实被物流记录支持",
      "confidence": 0.88
    }
  ],
  "evidence_assessment": [
    {
      "evidence_id": "EVD_MERCHANT_001",
      "assessment": "可支持物流系统签收状态，但不足以单独证明用户本人实际收到",
      "weight": "MEDIUM"
    }
  ],
  "policy_application": [
    {
      "rule": "签收争议举证责任",
      "application": "应结合签收凭证、签收人身份、投递照片和物流记录判断"
    }
  ],
  "remaining_uncertainties": [
    "签收人身份仍需后续确认"
  ],
  "reviewer_attention": [
    "复核签收凭证是否足以证明用户本人或授权人签收"
  ],
  "non_final_notice": "此处为 AI 生成的非最终裁决草案，后续确认后才会形成最终结果。"
}
```

### 展示要求

- 当事人 outcome 页：展示草案、事实认定、证据采信、规则适用、后续确认关注。
- 审核员 review 页：展示完整草案、评审团复核、风险标记、模型调用信息、审计日志。
- 当事人页不展示技术错误、HTTP 错误、内部枚举、prompt、agent run 内部细节。

### 验收标准

- 结果页能解释“为什么倾向退款/拒退/补发/赔付”。
- 每个事实认定能追到证据或缺口。
- 每个证据采信意见能追到 evidence_id。
- 高风险和低置信裁决必须展示后续确认关注点。

---

## 5. 数据模型补强

### 5.1 case_intake_dossier

建议字段：

- `case_id`
- `dossier_version`
- `source_agent_session_id`
- `source_actor_id`
- `dossier_json`
- `quality_score`
- `ready_for_next_step`
- `frozen_at`
- `created_at`

### 5.2 evidence_dossier

建议字段：

- `case_id`
- `dossier_version`
- `baseline_version`
- `supersedes_version`
- `matrix_json`
- `revision_reason`
- `revision_summary_json`
- `updated_after_round`
- `updated_by_agent`
- `overall_confidence_score`
- `active`
- `frozen_at`
- `created_at`

规则：

- 开庭装卷生成或引用的版本是 `baseline_version`。
- 第二轮证据书记官复核后生成新版本，并将旧版本 `active=false`、新版本 `active=true`。
- 法官 agent run 必须记录实际使用的 `evidence_dossier_version`。
- 庭审卷轴必须记录每次矩阵版本变更，方便审核员复盘“法官当时基于哪版证据断案”。

### 5.3 hearing_round

建议字段：

- `case_id`
- `round_no`
- `round_stage`
- `round_status`
- `opened_at`
- `deadline_at`
- `sealed_at`
- `sealed_reason`
- `user_submission_message_id`
- `merchant_submission_message_id`
- `judge_question_message_id`
- `judge_summary_message_id`
- `summary_json`

### 5.4 hearing_ledger

建议字段：

- `case_id`
- `ledger_version`
- `ledger_json`
- `ledger_hash`
- `created_at`

### 5.5 adjudication_draft

建议字段：

- `case_id`
- `draft_version`
- `recommended_decision`
- `confidence`
- `draft_text`
- `fact_findings_json`
- `evidence_assessment_json`
- `policy_application_json`
- `reviewer_attention_json`
- `agent_run_id`
- `created_at`

### 5.6 jury_review

建议新增：

- `case_id`
- `draft_id`
- `risk_level`
- `confidence_score`
- `consistency_passed`
- `bias_flags_json`
- `missing_evidence_flags_json`
- `revision_suggestions_json`
- `requires_human_review`
- `agent_run_id`
- `created_at`

### 5.7 agent_a2a_message

建议新增：

- `a2a_message_id`
- `case_id`
- `round_no`
- `from_agent`
- `to_agent`
- `message_type`
- `input_refs_json`
- `payload_json`
- `visibility`
- `agent_run_id`
- `created_at`

---

## 6. 接口合同

### 6.1 庭审主页

`GET /api/disputes/{caseId}/hearing`

返回：

- `status`
- `messages`
- `rounds`
- `my_evidence`
- `counterparty_evidence`
- `ledger_digest`
- `outcome_visibility`

### 6.2 提交陈述

`POST /api/disputes/{caseId}/hearing/rounds/{roundNo}/statement`

请求：

```json
{
  "content": "本轮陈述内容",
  "client_message_id": "uuid-for-idempotency"
}
```

规则：

- actor 只能提交自己的陈述。
- 当前轮不是 OPEN/WAITING_COUNTERPARTY 时拒绝。
- 重复 `client_message_id` 返回同一结果。

### 6.3 庭审补证

`POST /api/disputes/{caseId}/hearing/evidence-supplements`

请求：

```json
{
  "round_no": 2,
  "evidence_ids": ["EVD_001"],
  "note": "补充说明"
}
```

规则：

- 只允许提交当前 actor 可操作的证据。
- 补证不会替代本轮陈述。
- 补证事件入卷，并更新可见证据栏。

### 6.4 完成庭审

`POST /api/disputes/{caseId}/hearing/complete`

只返回状态，不直接伪造最终结果。

### 6.5 庭审卷轴

`GET /api/disputes/{caseId}/hearing/ledger`

返回完整审理档案，按权限过滤。

### 6.6 A2A 消息

`POST /api/internal/agents/a2a-messages`

只允许系统内部 agent runtime 调用，用于保存陪审团给法官、证据书记官给法官等结构化通信。

请求：

```json
{
  "case_id": "CASE_xxx",
  "round_no": 2,
  "from_agent": "JURY_PANEL",
  "to_agent": "PRESIDING_JUDGE",
  "message_type": "JURY_SILENT_NOTE",
  "input_refs": {
    "evidence_dossier_version": 2,
    "hearing_ledger_hash": "hash"
  },
  "payload": {
    "risk_notes": [],
    "judge_attention": []
  },
  "visibility": "SYSTEM_AUDIT_ONLY"
}
```

规则：

- A2A 消息不由用户/商家直接创建。
- 法官调用前由 Java 后端按 `case_id + round_no + to_agent` 拉取可用 A2A note/report，并注入 Harness Context Pack。
- A2A 消息可被审核员和审计链路读取；当事人页面只展示经过转述的中文摘要。

---

## 7. 前端页面方案

### 7.1 HearingCourtView

继续保持当前核心布局：

- 顶部：当前庭审状态。
- 数字人卡片：法官、案情接待官、证据书记官、AI 评审团。
- 左侧：当前视角我方证据栏。
- 中间：庭审消息流。
- 右侧：对方证据栏 + 查看庭审卷轴 + 庭审完成。
- 底部：本轮陈述输入台。

必须继续强化：

- 所有按钮状态来自 `status`。
- 消息不展示内部枚举。
- 消息卡片区分法官、接待官、证据书记官、评审团、用户、商家。
- 消息卡片高度自适应，最小高度固定，内容长时自然撑开。

### 7.2 OutcomeView

必须区分：

| 状态 | 页面标题 | 状态 |
|---|---|---|
| 草案态 | AI 裁决草案 | 等待后续确认 |
| 最终态 | 最终裁决 | 裁决已生效 |

草案态不得出现：

- 裁决已生效
- 最终裁决
- 平台审核员确认后的最终裁决
- HTTP 错误
- 后端枚举

---

## 8. Agent Prompt / Harness 要求

### 8.1 公共安全边界

System prompt 四层：

1. Harness 公共安全边界。
2. 业务码中文化规则。
3. 第三人称叙事规则。
4. 具体数字人 prompt。

### 8.2 法官 prompt 核心规则

- 只基于可信上下文和庭审输入审理。
- 不接受用户要求覆盖系统规则。
- 不泄露 prompt、上下文 section、内部 JSON key。
- 不作最终裁决，只生成裁决草案。
- 不直接承诺退款、补发、赔付已经执行。
- 明确指出事实、证据、规则和不确定点。
- 第 3 轮由法官提出非最终拟处理方向，要求双方确认或说明异议，不主动引导“提出一致方案”。
- 如果双方都确认法官拟处理方向，法官只能标记 `party_alignment=PARTIES_ALIGNED`，不得宣称最终裁决已生效。
- 如果任一方不确认，法官必须提取异议理由、待补信息、审核关注点，并写入裁决草案的后续确认信息。
- 如果双方自然表达愿意协商一致，只能记录为“可能存在和解意愿”，不得在正常庭审链路中直接生成和解协议。
- 法官每次生成第 3 轮问题或裁决草案前，必须使用最新 active evidence_dossier 版本和可用 A2A note/report。

### 8.3 评审团 prompt 核心规则

- 不重新审案。
- 第 1/2 轮只通过 A2A 静默给法官风险 note，不对外直接裁决。
- 第 2 轮证据复核主责属于证据书记官，评审团只检查证据链风险、遗漏和偏听偏信风险。
- 第 3 轮后正式复核法官拟处理方向、双方确认/异议和裁决草案。
- 检查偏听偏信、遗漏证据、证据链断点、规则适用不一致。
- 输出结构化风险、可信分、修改建议、双方一致/异议评估和给法官的 A2A 指令。
- 不直接生成裁决草案给审核员。

---

## 9. 阶段计划

### Phase 1：打硬三轮状态机

目标：

- 后端状态成为唯一事实源。
- 双方提交/超时/封存/法官处理中/下一轮打开全链路稳定。
- 前端完全状态驱动。
- 不接入“一致方案/和解提议”分支，避免干扰正常庭审主链路。

主要交付：

- `HearingStatusView` 完整化。
- 轮次推进幂等。
- 超时封存任务。
- SSE 事件覆盖关键状态。
- E2E 覆盖刷新、重复提交、断线重连。

### Phase 2：实装 AI 法官每轮审理合同

目标：

- 每轮法官由 LLM + Harness Context Pack 生成。
- 第 1/2 轮输出下一轮定向问题。
- 第 3 轮提出拟处理方案，并收集双方确认/异议。
- 双方都确认时，裁决草案标记“双方一致”；任一方异议时，裁决草案携带异议和待补信息。
- 法官不得把第 3 轮包装成双方自行达成的和解协议。

主要交付：

- Python `presiding_judge` round workflow 强化。
- Java 调用、schema 校验、fallback、agent_run 入库。
- 法官调用前重新拼接最新 active evidence_dossier 和可用 A2A note/report。
- prompt 微调日志。

### Phase 3：强化案情事实地图和证据证明矩阵

目标：

- 接待官稳定输出事实地图。
- 证据书记官稳定输出证明矩阵，并在第 2 轮证据解释后生成更新版本。
- 法官能按事实-证据关系审理。

主要交付：

- intake_dossier contract 扩展。
- evidence_dossier matrix 扩展。
- evidence_dossier 版本化、active version 读取、矩阵变更入卷。
- 证据评分和缺口可视化。

### Phase 4：接入 AI 评审团和后续确认链路

目标：

- 法官草案后强制评审团复核。
- 第 1/2 轮评审团通过 A2A 静默 note 给法官，第 3 轮后通过 A2A report 正式复核。
- 评审结果进入 review/outcome。
- 平台审核员页面可基于完整审理档案确认最终结果。

主要交付：

- jury_review 表。
- agent_a2a_message 表。
- jury agent contract。
- 法官读取 A2A report 后生成或修订裁决草案。
- review workbench 接入草案、评审团、卷轴。

### Phase 5：裁决解释与审计闭环

目标：

- 结果页可解释。
- 审核页可追溯。
- 申诉和审计可复盘。

主要交付：

- hearing ledger endpoint。
- ledger hash。
- 裁决解释链展示。
- 全链路验收脚本。

---

## 10. 验收清单

### 10.1 法官智能

- [ ] 法官每轮调用都有可追踪 `agent_run_id`。
- [ ] 法官输入包含 intake_dossier 和 evidence_dossier。
- [ ] 第 1/2 轮输出下一轮定向问题。
- [ ] 第 3 轮输出结构化裁决草案。
- [ ] 用户 prompt 注入不能改变法官系统规则。

### 10.2 三轮状态机

- [ ] 双方提交后自动封存。
- [ ] 超时后自动封存。
- [ ] 封存后不能修改本轮陈述。
- [ ] 重复提交幂等。
- [ ] 刷新/断线后状态一致。
- [ ] 第 3 轮名称和语义为“方案确认”：确认/异议法官拟处理方向，不是独立一致方案。
- [ ] 双方都确认时，裁决草案标记“双方一致”；任一方异议时，草案携带异议和待补信息。
- [ ] 正常庭审链路不出现“提出一致方案”按钮或状态推进。

### 10.3 证据矩阵

- [ ] 每份证据有真实性、关联性、完整性评分。
- [ ] 每个争议事实有支持证据、反驳证据和缺口。
- [ ] 庭审补证更新矩阵版本。
- [ ] 第 2 轮证据书记官复核后更新 evidence_dossier active version。
- [ ] 法官上下文包含最新矩阵，并记录实际使用的 `evidence_dossier_version`。

### 10.4 接待事实地图

- [ ] case_story 第三人称。
- [ ] timeline、known_facts、disputed_facts、missing_information 不为空。
- [ ] 低质量案情不能进入庭审。
- [ ] 不展示乱码或后端字段名。

### 10.5 评审团

- [ ] 裁决草案后触发评审团复核。
- [ ] 评审团输出风险等级、可信分、修改建议。
- [ ] 高风险必须进入后续确认。

### 10.6 卷轴审计

- [ ] 每轮问题、陈述、补证、小结、封存时间入卷。
- [ ] 卷轴能按权限展示。
- [ ] 审核员能看到完整审理档案。

### 10.7 权限/session

- [ ] 用户/商家私有聊天互不可见。
- [ ] 双方只能看庭审共享消息。
- [ ] 审核员可看完整案卷。
- [ ] 所有接口后端校验权限。

### 10.8 庭审完成

- [ ] 未完成三轮不能进入 outcome。
- [ ] 草案未生成时显示等待。
- [ ] 草案生成后可进入 outcome 草案页。
- [ ] outcome 草案页不显示最终裁决/已生效。

### 10.9 实时协作

- [ ] 双方提交状态实时同步。
- [ ] 法官处理中实时同步。
- [ ] 轮次封存实时同步。
- [ ] 断线重连可恢复。

### 10.10 裁决解释

- [ ] 事实认定可追到证据。
- [ ] 证据采信可追到 evidence_id。
- [ ] 规则适用可读。
- [ ] 后续确认关注点清晰。
- [ ] 不暴露 HTTP 错误、内部枚举、prompt 或技术栈细节。

---

## 11. 风险与决策

### 11.1 最大风险

最大风险不是 UI，而是 Agent 输出不稳定导致后端状态不可控。

解决策略：

- Agent 输出必须 schema 校验。
- Java 状态机永远是最终事实源。
- 模型失败必须进入可读 fallback。
- 所有 fallback 都要明确“草案/待确认”，不能伪装成最终裁决。

### 11.2 第二风险

证据矩阵质量不足，导致法官只能基于聊天摘要断案。

解决策略：

- 证据书记官的主任务改成“证明矩阵”，不是“文件点评”。
- 法官 prompt 强制按争议事实逐项审理。
- 裁决草案必须引用 evidence_id 或说明证据缺口。

### 11.3 第三风险

权限只在前端控制。

解决策略：

- 所有 endpoint 统一挂 `AuthenticatedActor`。
- 消息、证据、卷轴、草案按 visibility 过滤。
- 审核员全权限必须后端明确授权。

---

## 12. 2026-07-10 Task 10 复核记录

本节是十点任务完成后的统一复核记录，不另开分散方案文件。复核目标是确认当前分支 `codex/hearing-a2a-active-dossier` 已按“完成一项、验证一次、提交并推送一次”的节奏推进，并对剩余缺口做最后检查。

### 12.1 已推送任务证据

- Task 1：`3882a98 docs: register AI court implementation tasks`
- Task 2：`37d7111 fix: hand off approved reviews without auto execution`
- Task 3：`a259c7d feat: simulate execution assistant handoff`
- Task 4：`001c42f feat: simplify reviewer outcome plan editor`
- Task 5：`ba7003d feat: add read-only execution event observations`
- Task 6：`ca0a37c feat: capture third round review focus signals`
- Task 7：`1d0a636 feat: add formal jury A2A review reports`
- Task 8：`497be33 test: guard evidence dossier revision failure`
- Task 9：`4fd5555 feat: enrich hearing ledger event trail`
- Task 10 追加后端恢复修复：`e09f9e0 fix: recover final hearing rounds from initial cursor`

### 12.2 本轮复核命令

- Java targeted suite：
  `.\mvnw.cmd "-Dtest=RestClientHearingCourtAgentClientTest,HearingCourtOrchestratorTest,AgentA2AMessageServiceTest,HearingCollaborationIntegrationTest,PostReviewOrchestrationServiceIntegrationTest,CaseEventControllerTest,EvidenceDossierRevisionServiceTest" test`
  结果：26 tests, 0 failures, 0 errors。
- Frontend targeted suite：
  `pnpm --dir frontend exec vitest run src/api/rooms.test.js src/views/disputes/HearingCourtView.test.js src/views/disputes/OutcomeView.test.js`
  结果：47 tests, 0 failures。
- Python agent contract：
  `python -m pytest tests/test_api.py tests/agents/test_presiding_judge_round_turn.py -q`
  结果：12 passed, 1 warning。
- Final round recovery targeted suite（`java-api-service`）：
  `.\mvnw.cmd "-Dtest=HearingFinalRoundRecoveryServiceTest,HearingPersistenceIntegrationTest#finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql" test`
  结果：6 tests, 0 failures, BUILD SUCCESS。

### 12.3 十点任务复核结论

1. 总方案文档：已登记 D1-D7 与十点任务，后续方案变更继续在本文维护。
2. 审核确认链路：审核员确认后不再自动执行真实工具，转为执行专员助手移交状态。
3. Outcome 执行专员助手：前端已模拟“裁决已确认 → 已移交 → 处理中 → 执行成功”。
4. 审核员操作台：Outcome 审核员侧收敛为主处理方向和执行方案说明。
5. ToolRegistry / MCP 边界：Java 业务层持有执行工具，Python 只能观察 catalog / event，不直接执行业务副作用。
6. 第三轮意见轮：自然语言意见被提取为 `review_focus_signal`，进入陪审团复核范围，不直接采纳为裁决。
7. 陪审团 A2A：正式 `JURY_REVIEW_REPORT` 通过 A2A 写入，法官最终草案上下文读取该报告。
8. 第二轮证据矩阵 v2：第二轮封存后更新 active evidence dossier；损坏矩阵会失败关闭，不静默回退 v1。
9. 庭审卷轴：已合并 room messages 与 durable lifecycle events，覆盖补证、证据矩阵更新、陪审团报告、草案生成、审核确认/执行助手移交。
10. 全链路复核：本节记录了 Java / Frontend / Python 三组 targeted suite；当前未发现需要继续补齐的十点任务缺项。

### 12.4 E2E / 边界追加证据

- dev-local 边界：Java API 使用 `8080`，Vite 使用 `5173`；本地调试启动入口为 `scripts/dev-local.ps1`，Docker 只用于依赖服务和最终部署形态。
- 固定演示账号：`user-local`、`merchant-local`、`reviewer-local`；模拟导入 `count=1` 单条。当前不做 3 秒冷却、不做分布式锁，只保证单 Java 实例内串行/单条导入。
- live case：`CASE_671ac52627874756b548d2fb501fab65`；draft：`DRAFT_8abe897d74104073bc123c96ad35d7c0`；review task：`REVIEW_1e8e70da3af84f3d8ab89093db81c290`；packet：`PACKET_ba72a5f1dda3438695d6717d23917146`；remedy：`REMEDY_59a2a3b17678492590f848d399aec5b6`。
- 权限验证：`reviewer-local` 可以 `GET /api/reviews?status=PENDING`；`user-local` / `merchant-local` 返回 403。Outcome 对 party / reviewer 均无 controls 泄漏。
- 证据空校验：发起方证据为空时 evidence complete 返回 400，不能进入开庭。
- 语义边界复核：第三轮自然语言意见只作为陪审复核 focus，不直接采纳为裁决；评审/证据矩阵更新作为 A2A 上下文输入进入法官草案链路。

### 12.5 合并前工作树归属处理

- `IntakeRoomView.vue` 与对应测试属于接待室右侧展板的已确认 UI 收口，按独立前端提交纳入。
- `viteProxyConfig.test.js` 用于锁定本地 Vite 将 `/api` 代理到 Java `8080` 的 dev-local 边界，按独立测试提交纳入。
- `deploy/nginx/default.conf` 必须保留全量 Docker 环境的 `frontend:5173` 与 `java-api-service:8080` 容器 upstream；宿主机 Vite 代理实验不进入生产配置。
- `.codex/figma-previews/` 是本机生成的设计预览缓存，通过精确 `.gitignore` 规则排除，不纳入版本控制。

---

## 13. 推荐下一步

优先顺序：

1. 先打硬三轮状态机。
2. 再实装 AI 法官每轮输入/输出合同。
3. 然后加强 intake_dossier 和 evidence_dossier 结构化质量。
4. 接着接入 AI 评审团复核。
5. 最后补齐平台审核员终审和完整审计解释链。

推荐先做 Phase 1，因为状态机是法庭产品的脊梁。没有稳定状态机，法官智能、证据矩阵和评审团都会变成不可靠的漂浮组件。
