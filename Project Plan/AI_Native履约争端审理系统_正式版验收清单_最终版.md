# AI Native 履约争端审理系统：正式版验收清单（最终版）

> 文档性质：企业级架构、技术文档、后续实现与交付的统一验收清单  
> 适用范围：正式版架构文档、正式版开发文档、技术清单、配置说明、接口文档、测试文档、未来代码实现  
> 当前任务边界：本文档用于验收文档完整性和未来实现质量；不声明当前代码已经完成实现  
> API 命名原则：正式版 API 不使用 `/v2`、`/v3` 等路径标识

---

## 0. 使用说明

### 0.1 验收类型

本清单支持两类验收：

1. **文档验收**：检查正式版架构与技术文档是否完整、一致、无旧产品主线残留。
2. **实现验收**：未来代码按正式版文档开发后，检查功能、架构、安全、测试和运行结果。

当前阶段主要用于文档验收。不得把文档中的目标态描述当作当前代码已实现的证据。

### 0.2 状态定义

| 状态 | 含义 |
|---|---|
| PASS | 有直接、可复现证据证明满足 |
| FAIL | 有证据证明不满足 |
| PARTIAL | 只满足部分要求 |
| BLOCKED | 外部条件缺失且已明确阻塞原因 |
| N/A | 经说明后确认不适用 |

### 0.3 证据要求

每项验收至少记录：

```text
检查项编号
状态
证据路径或命令
关键输出
问题说明
整改建议
责任人
复验日期
```

以下内容不得作为通过证据：

```text
看起来合理
文档写了
理论上可以
模型回答说可以
没有报错所以通过
```

---

## 1. 一票否决项

任一项失败，最终验收结论必须为 FAIL。

- [ ] AI 不作最终裁决。
- [ ] AI 不直接执行退款、补发、关闭、驳回或通知。
- [ ] 全部业务动作均经过有效平台人审。
- [ ] Tool Executor 拒绝未审批、过期或被篡改动作。
- [ ] 不构成履约争端的请求转交后在本系统结束。
- [ ] Agent 不能越权读取其他案件或敏感数据。
- [ ] 模型密钥未进入仓库、前端、Prompt 或日志。
- [ ] 原始证据可追溯且不能被 Agent 摘要替代。
- [ ] 人工审核和执行动作具有完整审计记录。
- [ ] 正式版 API 不使用 `/v2`、`/v3` 等路径标识。
- [ ] 系统未重新扩展为订单中心、物流监控或泛售后工作台。

---

## 2. 文档完整性验收

### 2.1 产品定位

- [ ] 文档统一使用“AI Native 履约争端审理系统”。
- [ ] 文档明确系统只处理履约争端。
- [ ] 文档明确不做订单中心。
- [ ] 文档明确不做物流监控大盘。
- [ ] 文档明确不做泛售后工作台。
- [ ] 文档明确普通查物流、催发货、退款进度查询不属于主流程。
- [ ] 文档明确非争端请求只转交并在本系统终止。

### 2.2 主对象与主线

- [ ] 唯一主对象为 `FulfillmentDisputeCase`。
- [ ] 订单、物流、支付、售后均作为证据上下文，而非主对象。
- [ ] 主流程为争端发起 → 受理 → 卷宗 → 审理 → 评议 → 人审 → 执行 → 复盘。
- [ ] 三路径为 `TRANSFERRED`、`SIMPLE_HEARING`、`FULL_HEARING`。
- [ ] `TRANSFERRED` 是本系统终态。

### 2.3 统一术语

- [ ] Dispute Intake Officer Agent / 争议接待官。
- [ ] Evidence Clerk Agent / 证据书记官。
- [ ] AI Presiding Judge Agent / AI 主审官。
- [ ] AI Deliberation Panel / AI 评议团。
- [ ] Review Copilot Agent / 审核辅助官。
- [ ] Evaluation Agent / 离线复盘官。
- [ ] Agent Runtime Harness。
- [ ] DeliberationReport。
- [ ] ReviewPacket。
- [ ] Tool Gateway。
- [ ] Human Interrupt。

### 2.4 API 文档命名

- [ ] 外部 API 使用 `/api/disputes`。
- [ ] 外部审核 API 使用 `/api/reviews`。
- [ ] 内部 Agent API 使用 `/internal/agents/...`。
- [ ] 内部证据解析 API 使用 `/internal/evidence/...`。
- [ ] 不出现 `/api/v2`、`/api/v3`、`/internal/v2`、`/internal/v3` 等路径。
- [ ] API 版本治理通过 Header、Schema、Profile、Prompt、Skill 和 Ruleset 版本表达。

---

## 3. 架构原则验收

### 3.1 基本分工

- [ ] Workflow 负责状态、流程、等待、超时、恢复、审计。
- [ ] Agent 负责非结构化理解、证据分析、草案生成。
- [ ] Harness 负责上下文、记忆、工具、权限、Guardrail、Trace。
- [ ] Skill 负责场景化审理方法和证据模板。
- [ ] Human Review 负责高风险动作最终确认。
- [ ] Tool Executor 负责执行已审批确定性动作。

### 3.2 红线

- [ ] Agent 不最终裁决。
- [ ] Workflow 不承载开放式认知。
- [ ] Human Review 不可绕过。
- [ ] Tool Executor 不接受未审批动作。
- [ ] Python Agent Service 不直连生产业务库执行写操作。
- [ ] Elasticsearch 和 Redis 不作为业务事实源。

### 3.3 C1-C6 表达

- [ ] C1-C6 被定义为审理 Stage，而不是六个独立 Agent。
- [ ] C1-C6 由 DisputeHearingWorkflow 控制。
- [ ] C1-C6 由 AI 主审官调用对应 Skill 完成。
- [ ] 每个 Stage 有独立 Schema、Prompt、Skill 和 Trace。

---

## 4. 服务与技术栈验收

### 4.1 服务边界

- [ ] Frontend 只承载交互，不读取服务密钥。
- [ ] Java API Service 是业务事实、权限、审批和执行责任服务。
- [ ] Python Agent Service 不持有最终业务状态。
- [ ] OCR Parser 只生成证据派生内容。
- [ ] Temporal 负责状态、等待、超时、重试和恢复。
- [ ] LiteLLM 是唯一模型访问入口。
- [ ] Langfuse 记录 Agent Trace，不作为业务事实源。
- [ ] PostgreSQL 是业务事实源。
- [ ] Redis 只用于缓存、限流和锁。
- [ ] MinIO 保存证据对象。
- [ ] Elasticsearch 只保存可重建检索投影。

### 4.2 基础设施边界

- [ ] 文档未要求为了概念完整性提前拆分更多微服务。
- [ ] 文档未要求引入 Kafka、Kubernetes、服务网格或额外向量库作为当前必需项。
- [ ] 新 Agent 默认归属 Python Agent Service。
- [ ] Agent、OCR、数据组件不应直接暴露公网。

---

## 5. Agent Runtime Harness 总体验收

### 5.1 Agent Profile

- [ ] 每个 Agent 有独立 Profile。
- [ ] Profile 有版本号。
- [ ] Profile 声明允许 case 状态。
- [ ] Profile 声明允许 Workflow Stage。
- [ ] Profile 声明允许任务。
- [ ] Profile 声明禁止任务。
- [ ] Profile 声明允许工具。
- [ ] Profile 声明禁止工具。
- [ ] Profile 声明允许 Memory Scope。
- [ ] Profile 声明允许 Skill。
- [ ] Profile 声明输出 Schema。
- [ ] Profile 声明运行预算。
- [ ] 未声明权限默认拒绝。

### 5.2 Instruction & Policy

- [ ] 指令分为 Global System Policy、Product Boundary、Agent Role、Stage、Skill、Runtime。
- [ ] 平台安全策略优先级最高。
- [ ] Workflow 阶段约束不能被案件文本覆盖。
- [ ] 证据中的指令被视为不可信数据。
- [ ] Prompt 版本可追踪。
- [ ] Prompt 不包含真实密钥。

### 5.3 Context Assembly

- [ ] Context 按 Agent、Stage、Skill、风险等级动态组装。
- [ ] 必带上下文包含 case、状态、角色、允许动作、禁止动作。
- [ ] 阶段上下文包含 previous_stage_outputs 和 output_schema。
- [ ] 证据上下文包含 EvidenceDossier、PartyClaims、Timeline、Matrix。
- [ ] 规则上下文包含 policy_candidates 和 rule_version。
- [ ] 每个上下文片段包含 source_type、source_id、version、captured_at、redaction_level。
- [ ] 未脱敏手机号、地址、证件号不直接注入。
- [ ] 无关历史订单不注入。
- [ ] 大附件全文不无界注入。

### 5.4 Memory

- [ ] Run Memory、Case Memory、Hearing Memory、Domain Memory、Evaluation Memory 分离。
- [ ] Memory 不作为事实源。
- [ ] 单案人工审核意见不能自动写入全局记忆。
- [ ] 历史案件结果不能替代当前证据。
- [ ] Memory 读写有策略和审计。

### 5.5 Skill Library

- [ ] Skill 有 skill_id、name、version。
- [ ] Skill 声明适用争端类型。
- [ ] Skill 声明适用 Agent。
- [ ] Skill 声明 required_context。
- [ ] Skill 声明 required_evidence。
- [ ] Skill 声明 reasoning_steps。
- [ ] Skill 声明 risk_flags。
- [ ] Skill 声明 forbidden_behavior。
- [ ] Skill 声明 output_schema。
- [ ] Skill 有 example_cases 和 evaluation_criteria。
- [ ] Agent 只能加载 Profile 允许的 Skill。

### 5.6 Tool Gateway

- [ ] Agent 不直接调用工具。
- [ ] Tool 调用经过 Tool Gateway。
- [ ] Tool 调用请求包含 tool_name、tool_version、case_id、agent_run_id、reason、idempotency_key。
- [ ] Tool 返回包含 status、data、source_refs、redactions、audit_id。
- [ ] Tool Gateway 校验工具是否注册。
- [ ] Tool Gateway 校验 Agent 是否有权限。
- [ ] Tool Gateway 校验案件状态是否允许。
- [ ] Tool Gateway 校验参数 Schema。
- [ ] Tool Gateway 校验是否需要人审。
- [ ] Tool Gateway 校验幂等。
- [ ] Tool Gateway 校验越权数据访问。
- [ ] Tool Gateway 对结果脱敏。

### 5.7 Agent Loop

- [ ] Loop 包含 load_profile、validate_state、read_memory、build_context、load_skill、call_model、validate_output、guardrail、tool_call、observe、write_memory、stop_check。
- [ ] Loop 有 max_iterations。
- [ ] Loop 有 max_tool_calls。
- [ ] Loop 有 max_model_calls。
- [ ] Loop 有 token budget。
- [ ] Loop 有 max_runtime_seconds。
- [ ] Loop 检测重复调用同一工具且无新增信息。
- [ ] Loop 支持 schema 修复次数上限。
- [ ] Loop 支持需要人工时中断。

### 5.8 Structured Output & Validation

- [ ] 所有 Agent 输出统一 Envelope。
- [ ] Schema 校验字段、类型、枚举、必填。
- [ ] 引用校验证据、规则、版本真实存在。
- [ ] 业务校验状态、权限、禁止行为、金额、动作一致性。
- [ ] 校验失败不会伪装成成功。
- [ ] 修复次数用尽后中断到人工或失败状态。

### 5.9 Guardrail

- [ ] Input Guardrail 覆盖提示注入、越权请求、无关请求、敏感信息。
- [ ] Context Guardrail 覆盖隐私误暴露。
- [ ] Tool Guardrail 覆盖工具权限、参数、审批、幂等。
- [ ] Output Guardrail 覆盖承诺退款、最终裁决、站队、泄露隐私。
- [ ] Risk Guardrail 覆盖高价值、掉包、签收未收到、欺诈、法律风险。
- [ ] Guardrail 命中有 trace 和审计。

### 5.10 Human Interrupt

- [ ] 支持 need_user_evidence。
- [ ] 支持 need_merchant_evidence。
- [ ] 支持 need_platform_review。
- [ ] 支持 need_risk_review。
- [ ] 支持 approval_required。
- [ ] 支持 draft_revision_required。
- [ ] 支持 manual_takeover_required。
- [ ] Interrupt Payload 包含 case_id、review_packet_id、action_type、reason_summary、risk_flags、allowed_decisions。

### 5.11 Lifecycle Hooks

- [ ] before_agent_run 可初始化 run_id、权限、预算、trace。
- [ ] before_context_build 可决定上下文和脱敏策略。
- [ ] after_context_build 记录上下文来源和 token。
- [ ] before_llm_call 注入指令、Skill、工具清单。
- [ ] after_llm_call 记录模型输出、token、延迟。
- [ ] before_tool_call 做权限、参数、幂等、审批检查。
- [ ] after_tool_call 记录工具结果、错误、重试。
- [ ] before_memory_read 判断读取哪些记忆。
- [ ] after_memory_write 写入案件摘要或评估信号。
- [ ] before_human_interrupt 构造审批卡。
- [ ] after_human_resume 写入人工决策并恢复 Workflow。
- [ ] on_guardrail_violation 降级、转人工或拒绝执行。
- [ ] on_agent_error 重试、失败诊断、安全中断。
- [ ] after_agent_run 输出结果、trace、评估样本。

### 5.12 Observability

- [ ] 每次 Agent Run 记录 workflow_trace_id。
- [ ] 每次 Agent Run 记录 agent_run_id。
- [ ] 每次 Agent Run 记录 model_name 和 model_params。
- [ ] 每次 Agent Run 记录 prompt_version。
- [ ] 每次 Agent Run 记录 skill_version。
- [ ] 每次 Agent Run 记录 agent_profile_version。
- [ ] 每次 Agent Run 记录 context_sources。
- [ ] 每次 Agent Run 记录 tool_calls。
- [ ] 每次 Agent Run 记录 guardrail_events。
- [ ] 每次 Agent Run 记录 token_usage、latency、cost。
- [ ] Trace 可通过 case_id 和 agent_run_id 查询。

---

## 6. 具体 Agent 验收

### 6.1 争议接待官 Agent

- [ ] 只在争端提交或基础信息补充后触发。
- [ ] 能区分争端和普通查询。
- [ ] 能抽取 initiator_role。
- [ ] 能抽取 order_reference。
- [ ] 能抽取 user/merchant claim。
- [ ] 能识别 requested_remedy。
- [ ] 能输出 ACCEPTED、NEED_MORE_INFO、TRANSFERRED。
- [ ] 输出引用提交原文。
- [ ] 不判断谁对谁错。
- [ ] 不承诺退款或补发。
- [ ] 不调用写工具或执行工具。
- [ ] 工具失败时标记不确定，不臆造事实。

### 6.2 证据书记官 Agent

- [ ] 能构建 EvidenceDossier。
- [ ] 能生成证据目录。
- [ ] 能生成案件时间线。
- [ ] 能整理双方主张。
- [ ] 能构建 Claim-Issue-Evidence Matrix。
- [ ] 能识别缺失证据。
- [ ] 能识别证据冲突。
- [ ] 能识别重复证据。
- [ ] 原件、解析文本和 Agent 摘要分层保存。
- [ ] 每项事实有证据引用或标为当事方陈述。
- [ ] 卷宗更新创建新版本。
- [ ] 不输出责任归属。
- [ ] 不输出退款、补发、驳回建议。

### 6.3 AI 主审官 Agent

- [ ] 只在 FULL_HEARING 或草案修订阶段触发。
- [ ] C1 输出 IssueFramingResult。
- [ ] C2 输出 EvidenceGapResult。
- [ ] C3 输出 EvidenceRequestDraftResult。
- [ ] C4 输出 EvidenceCrossCheckResult。
- [ ] C5 输出 RuleApplicationResult。
- [ ] C6 输出 AdjudicationDraftResult。
- [ ] C6 必须包含 `non_final=true`。
- [ ] 每个 Stage 使用冻结卷宗版本。
- [ ] 每个 Stage 有独立 Prompt、Skill、Schema、Trace。
- [ ] 每个事实认定引用证据。
- [ ] 每条规则适用引用规则版本。
- [ ] 不输出最终裁决。
- [ ] 不承诺退款或补发。
- [ ] 不创造不存在的规则。
- [ ] 低置信度触发评议团或人工重点审核。

### 6.4 AI 评议团

- [ ] 仅按风险触发，不默认全量触发。
- [ ] 所有 Critic 使用同一冻结输入。
- [ ] Evidence Critic 只审查证据链。
- [ ] Rule Critic 只审查规则适用。
- [ ] Risk Critic 只审查风险。
- [ ] Remedy Critic 只审查执行方案一致性。
- [ ] Fairness Critic 只审查公平性和一致性。
- [ ] Critic 不审批。
- [ ] Critic 不执行。
- [ ] Critic 不输出最终裁决。
- [ ] 单个 Critic 失败不视为无异议。
- [ ] Critic 超时在报告中标记。
- [ ] 重大异议不得被平均分抵消。
- [ ] Panel Aggregator 保留少数高严重度意见。
- [ ] 输出 DeliberationReport。

### 6.5 审核辅助官 Agent

- [ ] 只服务平台审核员。
- [ ] 只读取当前授权 ReviewPacket。
- [ ] 回答引用证据、规则、草案、评议项。
- [ ] 区分事实、推断和建议。
- [ ] 标明不确定性。
- [ ] 不替审核员批准。
- [ ] 不替审核员驳回。
- [ ] 不修改 RemedyPlan。
- [ ] 不触发 Tool Executor。

### 6.6 Evaluation Agent

- [ ] 只在案件关闭后运行。
- [ ] 输入使用脱敏快照。
- [ ] 分析 Agent Run。
- [ ] 分析人审修改。
- [ ] 分析执行结果。
- [ ] 输出质量评分。
- [ ] 输出错误分类。
- [ ] 输出 Prompt/Skill/规则改进建议。
- [ ] 不自动修改线上 Prompt。
- [ ] 不自动发布 Skill。
- [ ] 不参与当前案件实时处理。

---

## 7. Router 与三路径验收

### 7.1 TRANSFERRED

- [ ] 不满足受理条件时输出转交原因。
- [ ] 转交目标明确。
- [ ] 转交后成为本系统终态。
- [ ] 不生成 RemedyPlan。
- [ ] 不进入 Platform Human Review。
- [ ] 不触发 Tool Executor。

### 7.2 SIMPLE_HEARING

- [ ] 只用于规则清晰、证据充分、风险较低的争端。
- [ ] 仍构建 EvidenceDossier。
- [ ] 仍生成结构化事实。
- [ ] 仍经过 Remedy Planner。
- [ ] 仍经过 Approval Policy Engine。
- [ ] 仍经过 Platform Human Review。
- [ ] 仍经过 Tool Executor。
- [ ] 不因“简易”取消审计或人审。

### 7.3 FULL_HEARING

- [ ] 事实冲突进入完整审理。
- [ ] 缺证进入完整审理。
- [ ] 规则不清进入完整审理。
- [ ] 高风险进入完整审理。
- [ ] 执行 C1-C6。
- [ ] 达到风险条件时触发评议团。
- [ ] 重大异议进入修订或人工复核。

---

## 8. Workflow 验收

### 8.1 FulfillmentDisputeWorkflow

- [ ] 支持幂等启动。
- [ ] 编排争端提交、受理、卷宗、路由、审理、评议、人审、执行、关闭。
- [ ] 状态转换合法。
- [ ] Agent 失败不会越过人审。
- [ ] 等待补证不占用工作线程。
- [ ] 等待人审不占用工作线程。
- [ ] Workflow Replay 不执行非确定性逻辑。

### 8.2 DisputeHearingWorkflow

- [ ] C1-C6 阶段可追踪。
- [ ] 补证等待使用 Signal/Timer。
- [ ] 补证次数有上限。
- [ ] 补证总时长有上限。
- [ ] 补证完成后更新 EvidenceDossier 版本。
- [ ] AI 主审官基于冻结版本继续审理。
- [ ] 可从检查点恢复。

### 8.3 DeliberationPanelWorkflow

- [ ] 能冻结输入快照。
- [ ] 能按风险选择 Critic。
- [ ] 能并行运行 Critic。
- [ ] 能收集 Critic 报告。
- [ ] 能聚合 DeliberationReport。
- [ ] 单成员失败不等同无异议。
- [ ] 超时和部分失败可见。

### 8.4 HumanReviewWorkflow

- [ ] ReviewPacket 不可变。
- [ ] Signal 校验 reviewer_id。
- [ ] Signal 校验 packet_version。
- [ ] Signal 校验 approved_action_hash。
- [ ] 支持批准。
- [ ] 支持修改后批准。
- [ ] 支持退回补证。
- [ ] 支持拒绝。
- [ ] 支持升级。
- [ ] 过期或被篡改 Signal 被拒绝。

### 8.5 ExecutionWorkflow

- [ ] 只接收批准动作。
- [ ] 按动作依赖顺序执行。
- [ ] 使用幂等键。
- [ ] 可重试和不可重试错误分类。
- [ ] 外部结果未知时先查询后处理。
- [ ] 不可逆动作不得自动补偿。
- [ ] 执行结果写入 ActionRecord。

---

## 9. Remedy、Policy、人审与执行验收

### 9.1 Remedy Planner

- [ ] 只把已形成的审理结果映射为动作。
- [ ] 不修改事实。
- [ ] 不修改规则适用。
- [ ] 校验动作组合。
- [ ] 校验金额。
- [ ] 校验重复动作。
- [ ] 校验动作依赖。
- [ ] 输出 approval_required。

### 9.2 Approval Policy Engine

- [ ] 输出风险等级。
- [ ] 输出审核角色。
- [ ] 输出审核人数。
- [ ] 输出允许动作。
- [ ] 所有正式版动作 `autoApprove=false`。
- [ ] 高金额触发平台审核。
- [ ] 高风险触发风控/高级审核。
- [ ] 策略只决定审核路径，不做事实判断。

### 9.3 Platform Human Review

- [ ] 审核员看到案件摘要。
- [ ] 审核员看到双方主张。
- [ ] 审核员看到证据卷宗。
- [ ] 审核员看到规则版本。
- [ ] 审核员看到 AI 主审官草案。
- [ ] 审核员看到 AI 评议团异议。
- [ ] 审核员看到 RemedyPlan。
- [ ] AI 与人工内容视觉区分。
- [ ] 审核动作必须填写理由。
- [ ] 高风险支持双人复核或升级。

### 9.4 Tool Executor

- [ ] 未审批动作拒绝。
- [ ] 过期审批拒绝。
- [ ] action_hash 不匹配拒绝。
- [ ] 审核员权限不足拒绝。
- [ ] 案件状态不允许时拒绝。
- [ ] 幂等键防止重复执行。
- [ ] 请求、响应、外部流水号和错误完整记录。
- [ ] 不接受模型自由文本命令。

---

## 10. 数据与证据验收

### 10.1 核心对象

- [ ] fulfillment_dispute_case。
- [ ] dispute_submission。
- [ ] party_claim。
- [ ] evidence_item。
- [ ] evidence_dossier。
- [ ] case_timeline_event。
- [ ] issue。
- [ ] claim_issue_evidence_link。
- [ ] evidence_request。
- [ ] hearing_state。
- [ ] hearing_stage_record。
- [ ] adjudication_draft。
- [ ] deliberation_report。
- [ ] deliberation_finding。
- [ ] remedy_plan。
- [ ] remedy_action。
- [ ] approval_policy_decision。
- [ ] review_packet。
- [ ] human_review_record。
- [ ] action_record。
- [ ] agent_run。
- [ ] agent_tool_call。
- [ ] agent_guardrail_event。
- [ ] evaluation_record。
- [ ] audit_log。

### 10.2 版本与一致性

- [ ] EvidenceDossier 版本化。
- [ ] AdjudicationDraft 版本化。
- [ ] DeliberationReport 版本化。
- [ ] ReviewPacket 版本化。
- [ ] RemedyPlan 版本化。
- [ ] HumanReviewRecord 不可覆盖。
- [ ] ActionRecord 不可覆盖。
- [ ] ReviewPacket 引用冻结版本。
- [ ] PostgreSQL 是事实源。
- [ ] MinIO 原件有哈希。
- [ ] Elasticsearch 投影可重建。
- [ ] Redis 数据丢失不破坏长期事实。

### 10.3 证据链

- [ ] 每项事实区分平台事实、当事方陈述和 Agent 推断。
- [ ] 摘要可回溯原件。
- [ ] 解析失败和警告可见。
- [ ] 上传有审计。
- [ ] 下载有审计。
- [ ] 解析有审计。
- [ ] 引用有审计。

---

## 11. API 验收

### 11.1 外部 API

- [ ] 使用 `/api/disputes`。
- [ ] 使用 `/api/reviews`。
- [ ] 不使用 `/api/v2`。
- [ ] 不使用 `/api/v3`。
- [ ] 写请求支持 `Idempotency-Key`。
- [ ] 返回 `requestId`。
- [ ] 返回 `traceId`。
- [ ] 金额使用最小货币单位。
- [ ] 错误不暴露密钥、Prompt 或堆栈。

### 11.2 内部 Agent API

- [ ] 使用 `/internal/agents/intake/analyze`。
- [ ] 使用 `/internal/agents/evidence/build`。
- [ ] 使用 `/internal/agents/hearing/run-stage`。
- [ ] 使用 `/internal/agents/deliberation/run`。
- [ ] 使用 `/internal/agents/review-copilot/query`。
- [ ] 使用 `/internal/agents/evaluation/analyze`。
- [ ] 内部 API 使用服务身份。
- [ ] 请求和响应有版本化 Schema。
- [ ] Python API 不提供审批入口。
- [ ] Python API 不提供执行入口。

### 11.3 Parser API

- [ ] 使用 `/internal/evidence/parse`。
- [ ] 使用 `/internal/evidence/tasks/{taskId}`。
- [ ] 异步解析任务可查询。
- [ ] 返回派生内容、来源引用和警告。
- [ ] 不返回责任判断。

---

## 12. 前端验收

- [ ] 有争端发起入口。
- [ ] 有争端案件列表。
- [ ] 有争端案件工作台。
- [ ] 有 AI 证据工作室。
- [ ] 有 AI 审理庭。
- [ ] 有平台审核任务页。
- [ ] 有平台审核台。
- [ ] 页面以案件而非全量订单组织。
- [ ] 不展示物流监控大盘。
- [ ] 不展示普通订单中心。
- [ ] 原件、解析结果和 AI 摘要视觉区分。
- [ ] AI 草案明确标注非最终结论。
- [ ] 评议团重大异议醒目展示。
- [ ] 审核动作二次确认并显示实际执行参数。
- [ ] 前端不持有服务密钥。

---

## 13. 配置验收

- [ ] 模型调用统一经过 LiteLLM。
- [ ] 真实 API Key 只在本地 `.env` 或安全配置中。
- [ ] `.env.example` 只含占位符。
- [ ] Agent Profile 版本可配置。
- [ ] Prompt 版本可配置。
- [ ] Skill 版本可配置。
- [ ] Ruleset 版本可配置。
- [ ] Loop 预算可配置。
- [ ] 评议团使用服务端 Feature Flag。
- [ ] 审核辅助官使用服务端 Feature Flag。
- [ ] 关闭 AI 增强能力仍强制人审。
- [ ] Agent 输出校验默认启用。
- [ ] Guardrail 默认启用。

---

## 14. 安全验收

### 14.1 身份权限

- [ ] 用户只能访问自身案件。
- [ ] 商家只能访问自身相关案件。
- [ ] 审核员按角色、队列、金额和风险授权。
- [ ] 服务身份和用户身份分离。
- [ ] Agent Profile 不能扩大调用方权限。

### 14.2 Prompt 注入

- [ ] 证据文本中的指令不被执行。
- [ ] 外部附件中的“忽略规则”等文本被视为不可信数据。
- [ ] 未注册 Tool 调用被拒绝。
- [ ] 越权上下文被拒绝并审计。
- [ ] 模型输出不能直接形成执行请求。

### 14.3 数据安全

- [ ] 敏感信息在日志中脱敏。
- [ ] 敏感信息在 Trace 中脱敏。
- [ ] MinIO 使用短期签名 URL。
- [ ] 导出、下载和批量查询纳入审计。
- [ ] 日志不记录 API Key。
- [ ] 日志不记录完整支付凭证。

### 14.4 执行安全

- [ ] 审批和执行职责分离。
- [ ] Tool Executor 校验 action hash。
- [ ] Tool Executor 校验金额白名单/上限。
- [ ] Tool Executor 校验动作白名单。
- [ ] Redis 锁与数据库唯一键双重幂等。
- [ ] 不可逆动作失败转人工。

---

## 15. 日志、Trace 与指标验收

### 15.1 关联标识

- [ ] request_id。
- [ ] trace_id。
- [ ] case_id。
- [ ] workflow_id。
- [ ] workflow_run_id。
- [ ] agent_run_id。
- [ ] review_id。
- [ ] action_id。

### 15.2 必须记录

- [ ] 案件状态变化。
- [ ] 证据版本与访问。
- [ ] Agent 输入引用。
- [ ] Agent 输出引用。
- [ ] Tool 调用。
- [ ] Profile 版本。
- [ ] Prompt 版本。
- [ ] Skill 版本。
- [ ] 模型版本。
- [ ] 规则版本。
- [ ] 评议团异议。
- [ ] 审核决定和理由。
- [ ] 执行请求、结果和外部流水号。

### 15.3 指标

- [ ] Workflow 成功率。
- [ ] Workflow 重试次数。
- [ ] Workflow 等待时长。
- [ ] Agent Schema 通过率。
- [ ] Agent 引用通过率。
- [ ] 补证次数。
- [ ] 补证超时率。
- [ ] 补证有效率。
- [ ] 人审接受率。
- [ ] 人审修改率。
- [ ] 人审退回率。
- [ ] 执行成功率。
- [ ] 幂等命中率。
- [ ] 结果未知率。
- [ ] 模型时延。
- [ ] Token 成本。
- [ ] 相似案件一致性。
- [ ] 公平性指标。

---

## 16. 测试验收

### 16.1 Agent Harness 测试

- [ ] Agent Profile 权限测试。
- [ ] Context Builder 脱敏测试。
- [ ] Context 来源记录测试。
- [ ] Memory Scope 测试。
- [ ] Skill 加载测试。
- [ ] Tool Gateway 权限测试。
- [ ] Loop 停止条件测试。
- [ ] Schema Validator 测试。
- [ ] Guardrail 测试。
- [ ] Hook 调用顺序测试。
- [ ] Trace 完整性测试。

### 16.2 Agent 业务评估

- [ ] 争议接待官受理准确率。
- [ ] 争议接待官误收普通查询率。
- [ ] 证据书记官证据引用准确率。
- [ ] 证据书记官缺证识别准确率。
- [ ] AI 主审官争点覆盖率。
- [ ] AI 主审官规则引用准确率。
- [ ] AI 主审官草案通过率。
- [ ] AI 评议团重大风险拦截率。
- [ ] AI 评议团无效触发率。
- [ ] 审核辅助官采纳率。
- [ ] 审核辅助官误导率。
- [ ] Evaluation Agent 改进建议可执行率。

### 16.3 Workflow 测试

- [ ] 不予受理终止。
- [ ] 简易审理闭环。
- [ ] 完整审理 C1-C6。
- [ ] 补证等待与恢复。
- [ ] 评议团并行与部分失败。
- [ ] 人审退回补证。
- [ ] 审批 packet 过期。
- [ ] 执行幂等。
- [ ] 外部结果未知处理。
- [ ] Workflow Replay。

### 16.4 安全测试

- [ ] 证据 Prompt 注入。
- [ ] 越权案件访问。
- [ ] 伪造证据引用。
- [ ] 伪造规则引用。
- [ ] 诱导 Agent 直接退款。
- [ ] 绕过 Human Review。
- [ ] 重复执行退款。
- [ ] 日志敏感信息泄露。
- [ ] 审批重放。

### 16.5 E2E 场景

- [ ] 用户只是查物流 → 转普通售后，本系统终止。
- [ ] 商家已同意退款但未执行 → 简易审理 → 人审 → 执行。
- [ ] 签收未收到 → 补证 → 完整审理 → 人审 → 执行。
- [ ] 退货掉包高风险 → AI 主审官 → AI 评议团 → 人审 → 执行。
- [ ] 平台审核员退回补证 → Workflow 暂停恢复 → 更新卷宗 → 继续审理。
- [ ] Tool Executor 超时但外部已成功 → 查询确认 → 避免重复执行。

---

## 17. 企业级非功能验收

### 17.1 性能

- [ ] 争议接待官常规响应 P95 <= 5s。
- [ ] 小型案件证据书记官初版卷宗生成 P95 <= 30s。
- [ ] AI 主审官单 Stage P95 <= 20s。
- [ ] AI 评议团高风险案件 P95 <= 60s 或异步展示。
- [ ] 审核台 ReviewPacket 查询 P95 <= 2s。

### 17.2 可用性

- [ ] Agent 服务不可用时案件可转人工。
- [ ] 评议团不可用时不能绕过人审。
- [ ] OCR 不可用时保留原件并提示人工查看。
- [ ] Langfuse 不可用时不影响主流程，本地审计仍保留。
- [ ] LiteLLM 不可用时不得跳过人审或执行。

### 17.3 成本控制

- [ ] 每个 Agent 有 token budget。
- [ ] 上下文按需检索，不全量塞入。
- [ ] AI 评议团按风险触发。
- [ ] 重复证据和大附件摘要化处理。
- [ ] Evaluation Agent 离线批处理。

### 17.4 可审计性

- [ ] 每条事实可回溯证据。
- [ ] 每条规则引用可回溯版本。
- [ ] 每次 Agent 输出可回溯 prompt、skill、model、profile。
- [ ] 每次人工修改可回溯 ReviewPacket 冻结版本。
- [ ] 每次执行可回溯 HumanReviewRecord 和 action_hash。

---

## 18. 文档交付验收

### 18.1 正式版开发文档

- [ ] 包含产品定位。
- [ ] 包含系统边界。
- [ ] 包含总体架构。
- [ ] 包含服务拓扑。
- [ ] 包含领域模型。
- [ ] 包含 Agent Runtime Harness。
- [ ] 包含每个 Agent 的 Harness 设定。
- [ ] 包含 AI 评议团细节。
- [ ] 包含 Workflow 设计。
- [ ] 包含无版本路径 API 设计。
- [ ] 包含数据模型。
- [ ] 包含前端交互。
- [ ] 包含错误码。
- [ ] 包含日志 Trace。
- [ ] 包含安全。
- [ ] 包含测试。
- [ ] 包含企业级非功能要求。

### 18.2 正式版验收清单

- [ ] 包含一票否决项。
- [ ] 包含文档验收。
- [ ] 包含架构原则验收。
- [ ] 包含 Agent Harness 验收。
- [ ] 包含每个 Agent 验收。
- [ ] 包含 Workflow 验收。
- [ ] 包含 API 验收。
- [ ] 包含数据验收。
- [ ] 包含前端验收。
- [ ] 包含安全验收。
- [ ] 包含测试验收。
- [ ] 包含非功能验收。
- [ ] 包含最终报告模板。

---

## 19. 验收报告模板

```markdown
# AI Native 履约争端审理系统正式版验收报告

## 1. 验收结论

PASS / FAIL / PARTIAL / BLOCKED

## 2. 验收范围

- Git commit：
- 环境：
- 服务版本：
- 模型版本：
- Agent Profile 版本：
- Prompt 版本：
- Skill 版本：
- 规则版本：

## 3. 一票否决项

| 编号 | 状态 | 证据 | 说明 |
|---|---|---|---|

## 4. 分类统计

| 分类 | PASS | FAIL | PARTIAL | BLOCKED | N/A |
|---|---:|---:|---:|---:|---:|

## 5. 失败与阻塞项

| 编号 | 现象 | 根因 | 影响 | 整改建议 |
|---|---|---|---|---|

## 6. 测试结果

| 测试集 | 命令 | 结果 | 报告 |
|---|---|---|---|

## 7. E2E 场景

| 场景 | 状态 | case_id | trace_id | 证据 |
|---|---|---|---|---|

## 8. 安全与审计结论

- 人审是否可绕过：
- Tool Executor 是否可被模型直接触发：
- 证据是否可追溯：
- 敏感信息是否泄露：

## 9. 最终建议

- 是否允许发布：
- 遗留风险：
- 复验要求：
```

---

## 20. 最终判定规则

### PASS

- 一票否决项全部通过。
- 所有必验项通过。
- 自动化测试和 E2E 通过。
- 无未披露高风险问题。

### FAIL

- 任一一票否决项失败。
- 存在可绕过人审的路径。
- 存在越权、重复执行或证据不可追溯。
- 核心链路不可运行。
- 正式版 API 仍使用 `/v2`、`/v3` 等路径标识。

### PARTIAL

非核心增强项未完成，但一票否决项、主链路和安全边界均通过；必须明确缺口和复验条件。

### BLOCKED

只有在依赖的外部服务、权限或环境确实不可获得，且已有直接证据时使用；不得用 BLOCKED 掩盖实现失败。
