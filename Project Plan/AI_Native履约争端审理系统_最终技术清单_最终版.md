# AI Native 履约争端审理系统：最终技术清单（最终版）

> 文档性质：正式版技术选型、服务边界、工程约束与技术清单  
> 适用形态：Docker Compose 本地正式版 / 企业级目标态架构  
> 当前任务边界：本文档只定义最终技术方案，不修改现有代码、依赖、部署或配置  
> API 命名原则：正式版接口不使用 `/v2`、`/v3` 等路径标识  

---

## 1. 技术定位

系统是以 `FulfillmentDisputeCase` 为唯一主对象的 **AI Native 履约争端审理系统**，不是订单中心、物流监控系统或泛售后平台。

最终技术主线：

```text
Workflow 控流程；
Agent 做认知；
Harness 管边界；
Skill 管专业方法；
Policy 做门控；
Human 做决策；
Tool 做执行；
Audit 做追责；
Evaluation 做改进。
```

系统只处理用户或商家主动发起、或外部接口导入的履约争端。不构成履约争端的请求标记为 `NOT_ADMISSIBLE` 并留档，本系统不邀请相对方、不开放后续房间。

---

## 2. 最终技术栈总览

### 2.1 应用层

| 组件 | 技术 | 最终职责 |
|---|---|---|
| frontend | Vue 3、Vue Router、TypeScript、Pinia、Element Plus、Vite、Vitest | AI Native 争端发起、案件工作台、证据工作室、审理庭、审核台 |
| java-api-service | Java 21、Spring Boot 3、Spring Security、Maven、MyBatis-Plus | 业务事实、API、权限、Workflow Worker、审批、执行、审计 |
| python-agent-service | Python 3.12、FastAPI、Pydantic、LangGraph | Agent Runtime Harness、六类 Agent、Skill、Guardrail、Trace |
| ocr-parser-service | Python、FastAPI、OCR/文档解析 | 证据 OCR、文档解析、哈希、脱敏、派生内容 |
| nginx | Nginx | 统一入口、反向代理、前后端路由隔离 |

### 2.2 Workflow、模型与观测

| 组件 | 技术 | 用途 | 边界 |
|---|---|---|---|
| Workflow | Temporal | 长流程、Signal、Timer、重试、恢复 | 不做开放式认知 |
| 模型代理 | LiteLLM Proxy | 唯一 LLM 网关 | 应用不直连模型厂商 |
| 模型 | qwen3.7-plus | 统一 LLM 模型 | 仅通过 LiteLLM 调用 |
| Agent 观测 | Langfuse | Prompt/Agent Trace、成本、评估关联 | 不保存业务最终状态 |

### 2.3 数据与基础设施

| 组件 | 用途 | 禁止用途 |
|---|---|---|
| PostgreSQL | 业务事实、版本、审批、执行、审计 | 不存大体积证据原件 |
| Redis | 缓存、限流、分布式锁、短期状态 | 不作长期事实源 |
| MinIO | 原始证据、脱敏证据、派生文件、导出包 | 不保存权限判断 |
| Elasticsearch | 案件/证据/规则检索投影 | 不作业务事实源 |
| Docker Compose | 本地正式版完整环境编排 | 不代表生产高可用终态 |

---

## 3. 最终服务拆分

### 3.1 frontend

必须包含：

```text
争端发起入口；
争端案件列表；
争端案件工作台；
AI 证据工作室；
AI 审理庭；
平台审核任务页；
平台审核台。
```

不得建设：

```text
全量订单中心；
物流监控大盘；
发货时效大盘；
商家经营分析；
泛售后工作台；
通用客服机器人。
```

前端定位：

```text
AI Native 人机协作工作空间，不是传统 CRUD 后台。
```

核心技术要求：

```text
Intent-first 输入；
动态工作区；
可解释审理过程；
证据可视化；
Agent 状态流式展示；
人审门控卡片；
评议团异议高亮；
AI 内容版本和 Trace 展示。
```

### 3.2 java-api-service

确定性业务职责：

```text
FulfillmentDisputeCase Service；
DisputeSubmission Service；
Evidence Metadata / Dossier Version Service；
Admissibility & Hearing Router；
Hearing State Service；
Remedy Planner；
Approval Policy Engine；
Review Service；
Tool Executor；
Permission Service；
Idempotency Service；
Audit Service；
Temporal Worker / Activities；
Frontend API。
```

红线：

```text
不能把模型草案当最终决定；
不能让 Agent 绕过状态机；
不能执行无有效人审记录的动作；
不能直接调用 Qwen 3.7 Plus；
不能读取模型厂商 API Key；
不能让前端绕过 Java 访问内部 Agent。
```

### 3.3 python-agent-service

核心职责：

```text
Agent Runtime Harness；
Dispute Intake Officer Agent；
Evidence Clerk Agent；
AI Presiding Judge Agent；
AI Deliberation Panel；
Review Copilot Agent；
Evaluation Agent；
Skill Loader；
Context Builder；
Tool Gateway Client；
Structured Output Validator；
Guardrail Checker；
Lifecycle Hooks；
Langfuse Trace Reporter。
```

红线：

```text
不直连业务数据库写状态；
不直接访问对象存储或外部业务接口；
不审批；
不退款；
不补发；
不关闭售后；
不把输出写成最终裁决；
输出必须结构化并有证据/规则引用。
```

### 3.4 ocr-parser-service

职责：

```text
图片 OCR；
PDF / Word / Excel 解析；
图片元数据提取；
视频关键帧抽取；
证据脱敏；
文件哈希；
重复证据检测；
解析警告输出。
```

边界：

```text
解析文本是派生内容；
原始文件始终保留；
OCR 不判断责任；
OCR 不生成处理方案。
```

### 3.5 temporal-server

承载 Workflow：

```text
FulfillmentDisputeWorkflow；
DisputeHearingWorkflow；
DeliberationPanelWorkflow；
HumanReviewWorkflow；
ExecutionWorkflow；
EvaluationEmitWorkflow。
```

职责：

```text
案件长流程；
补证等待；
人审等待；
Timer；
Signal；
重试；
失败恢复；
Workflow History。
```

### 3.6 litellm-proxy

职责：

```text
统一模型别名；
统一模型凭证；
统一超时；
统一重试；
统一模型访问审计；
对 Python Agent Service 提供 OpenAI-compatible 接口。
```

约束：

```text
唯一默认模型 qwen3.7-plus；
应用服务只调用 LiteLLM；
Qwen 思考模式由 LiteLLM 统一设置 enable_thinking=false；
模型供应商密钥不进入前端、Prompt、日志或数据库；
Java 不直接调用 LiteLLM 或 Qwen 3.7 Plus。
```

### 3.7 langfuse

记录：

```text
case_id；
trace_id；
agent_run_id；
Agent/Profile/Prompt/Skill/模型版本；
上下文来源；
工具调用；
Token；
时延；
成本；
输出校验结果；
Guardrail 事件。
```

边界：

```text
不保存业务最终状态；
敏感证据需要脱敏或只记录引用。
```

### 3.8 postgresql

业务事实源，覆盖：

```text
案件；
提交；
主张；
证据元数据；
卷宗版本；
争点；
补证；
审理阶段；
裁决草案；
评议报告；
执行方案；
审核包；
人审记录；
执行记录；
Agent Run；
工具调用；
评估；
审计。
```

### 3.9 redis

仅用于：

```text
幂等锁；
分布式锁；
限流；
短期缓存；
临时会话状态；
可丢失的运行态信息。
```

禁止：

```text
长期业务事实；
证据原件；
审计记录；
最终状态。
```

### 3.10 elasticsearch

用于：

```text
案件检索投影；
证据检索投影；
规则检索投影；
相似案件检索投影。
```

边界：

```text
不是事实源；
索引可重建；
索引失败通过 Outbox/重建修复；
不能作为人审唯一依据。
```

### 3.11 minio

用于：

```text
原始证据；
脱敏证据；
OCR 派生文件；
文档解析派生物；
审核导出包；
临时处理文件。
```

边界：

```text
对象键不是权限凭证；
下载使用短期签名 URL；
元数据和权限在 PostgreSQL。
```

### 3.12 nginx

职责：

```text
统一暴露前端；
代理 Java API；
代理受控 Agent API；
代理 OCR API；
隐藏内部服务；
统一跨域与请求头；
避免前端直接访问中间件。
```

---

## 4. Agent Runtime Harness 技术清单

### 4.1 Harness 必备模块

```text
Identity & Authority Profile；
Instruction & Policy Layer；
Context Assembly；
Memory Layer；
Skill Library；
Tool Gateway Client；
Agent Loop Controller；
Structured Output Validation；
Guardrails；
HITL Interrupt；
Lifecycle Hooks；
Observability；
Evaluation Feedback。
```

### 4.2 Profile 必填项

```text
agent_id；
agent_name；
role；
agent_version；
allowed_case_states；
allowed_workflow_stages；
allowed_context_scopes；
allowed_memory_scopes；
allowed_skills；
allowed_tools；
forbidden_tools；
forbidden_actions；
max_iterations；
max_tool_calls；
max_model_calls；
max_input_tokens；
max_output_tokens；
max_runtime_seconds；
output_schema；
risk_policy；
version。
```

### 4.3 Context 必填元数据

每个上下文片段必须包含：

```text
source_type；
source_id；
source_version；
captured_at；
access_scope；
redaction_level；
content_summary。
```

禁止：

```text
未脱敏隐私；
无来源摘要；
无关历史订单；
无界大附件；
越权商家/用户数据。
```

### 4.4 Memory 分层

```text
Run Memory；
Case Memory；
Hearing Memory；
Domain Memory；
Evaluation Memory。
```

约束：

```text
Memory 不是事实源；
单案人工意见不能自动成为全局经验；
历史案件结果不能替代当前证据。
```

### 4.5 Skill Library

必须包含或预留：

```text
signed_not_received；
return_swap_dispute；
damaged_goods_dispute；
missing_or_wrong_item；
merchant_refund_rejection；
evidence_request_generation；
rule_application；
review_packet_generation；
fairness_consistency_check。
```

每个 Skill 必须包含：

```text
skill_id；
skill_name；
version；
applicable_dispute_types；
applicable_agents；
required_context；
required_evidence；
reasoning_steps；
risk_flags；
forbidden_behavior；
output_schema；
example_cases；
evaluation_criteria。
```

### 4.6 Output 必过校验

```text
JSON/Pydantic Schema；
证据引用存在；
规则引用存在且版本匹配；
案件状态合法；
Agent 权限合法；
金额/日期/枚举/跨字段一致；
不包含最终裁决；
不包含越权动作；
置信度和不确定性表达完整。
```

### 4.7 必须中断条件

```text
关键证据真实性存疑；
规则缺失或冲突；
高金额/不可逆动作；
低置信度；
重大评议异议；
连续校验失败；
身份或权限异常；
数据范围异常；
工具结果未知且影响执行。
```

---

## 5. 最终 Agent 清单

### 5.1 Dispute Intake Officer Agent

职责：

```text
争端识别；
主体识别；
主张抽取；
诉求抽取；
初步风险识别；
受理建议。
```

禁止：

```text
判断责任；
承诺退款/补发；
调用写工具；
调用执行工具；
把普通查询变成争端。
```

### 5.2 Evidence Clerk Agent

职责：

```text
构建 EvidenceDossier；
生成时间线；
整理证据目录；
构建 Claim-Issue-Evidence Matrix；
识别缺证；
识别冲突；
识别重复；
标记解析异常。
```

禁止：

```text
输出责任归属；
输出执行建议；
隐藏不利证据。
```

### 5.3 AI Presiding Judge Agent

职责：

```text
C1 争点归纳；
C2 缺证识别；
C3 补证请求；
C4 证据交叉核验；
C5 规则适用；
C6 非最终草案生成；
评议后草案修订。
```

禁止：

```text
最终裁决；
直接执行；
创造规则；
绕过人审。
```

### 5.4 AI Deliberation Panel

包含：

```text
Evidence Critic；
Rule Critic；
Risk Critic；
Remedy Critic；
Fairness Critic。
```

职责：

```text
高风险案件并行质询；
生成 DeliberationReport；
保留重大异议；
为主审官修订和审核员决策提供参考。
```

禁止：

```text
审批；
执行；
直接改最终方案；
输出最终裁决。
```

### 5.5 Review Copilot Agent

职责：

```text
解释草案；
解释证据；
解释评议异议；
对比方案；
辅助生成审核意见；
回答审核员追问。
```

禁止：

```text
替审核员批准；
替审核员驳回；
触发执行。
```

### 5.6 Evaluation Agent

职责：

```text
离线复盘；
质量评分；
错误分类；
人机差异分析；
评议团有效性分析；
Prompt/Skill/规则改进建议。
```

禁止：

```text
参与当前案件；
自动修改线上 Prompt；
自动发布 Skill；
自动调整规则。
```

---

## 6. Workflow 清单

### 6.1 FulfillmentDisputeWorkflow

案件级主编排，负责：

```text
幂等启动；
受理；
卷宗；
路由；
审理；
评议；
人审；
执行；
关闭；
Evaluation Trace 触发。
```

### 6.2 DisputeHearingWorkflow

执行：

```text
C1 Issue Framing；
C2 Evidence Gap；
C3 Evidence Request；
WAITING_EVIDENCE；
C4 Evidence Cross-check；
C5 Rule Application；
C6 Draft Generation；
Risk Gate；
Draft Revision。
```

### 6.3 DeliberationPanelWorkflow

执行：

```text
冻结输入；
选择 Critic；
并行运行；
收集报告；
聚合 DeliberationReport；
返回主审官修订或人审。
```

### 6.4 HumanReviewWorkflow

负责：

```text
冻结 ReviewPacket；
等待审核员 Signal；
校验 packet_version；
校验 action_hash；
支持批准、修改后批准、退回补证、拒绝、升级。
```

### 6.5 ExecutionWorkflow

负责：

```text
接收已审批动作；
校验审批；
执行动作依赖图；
幂等控制；
外部结果未知处理；
写入 ActionRecord。
```

---

## 7. 前端技术清单

### 7.1 前端技术栈

```text
Vue 3；
TypeScript；
Vite；
Vue Router；
Pinia；
Element Plus；
Vitest；
SSE / WebSocket 可选用于 Agent 状态流；
Markdown/JSON 渲染白名单；
文件上传组件；
证据预览组件；
时间线组件；
矩阵组件；
Diff 组件；
审核动作确认组件。
```

### 7.2 AI Native 交互模块

```text
Intent Input：自然语言争端描述；
Dynamic Workspace：案件动态工作区；
Evidence Studio：AI 证据工作室；
Hearing Court：AI 审理庭；
Review Console：平台审核台；
Agent Status Stream：Agent 运行状态流；
Human Interrupt Card：人审/补证中断卡；
Deliberation Panel View：评议团异议视图；
Trace Drawer：Trace 与版本抽屉。
```

### 7.3 前端安全边界

```text
前端不持有模型密钥；
前端不持有服务密钥；
前端不直接调用模型；
前端不直接调用内部 Agent API；
前端不直接访问 MinIO 原始对象；
前端不直接访问 Elasticsearch；
前端只渲染白名单 UI Schema；
Agent 生成的 UI Schema 不能直接触发执行动作。
```

---

## 8. API 与通信

### 8.1 外部 API

正式版路径：

```text
POST /api/disputes
GET  /api/disputes/{caseId}
GET  /api/disputes
POST /api/disputes/{caseId}/evidence
GET  /api/disputes/{caseId}/evidence-dossiers/{version}
GET  /api/disputes/{caseId}/events
GET  /api/disputes/{caseId}/hearing

GET  /api/reviews
GET  /api/reviews/{reviewId}/packet
POST /api/reviews/{reviewId}/decision
POST /api/reviews/{reviewId}/copilot/query
```

### 8.2 内部 API

```text
POST /internal/agents/intake/analyze
POST /internal/agents/evidence/build
POST /internal/agents/hearing/run-stage
POST /internal/agents/deliberation/run
POST /internal/agents/review-copilot/query
POST /internal/agents/evaluation/analyze

POST /internal/evidence/parse
GET  /internal/evidence/tasks/{taskId}
```

约束：

```text
外部 API 经 Java API Service；
内部 Agent API 使用服务身份；
前端不能直接访问内部 API；
Python 不反向调用 Tool Executor；
Workflow 调用 Agent 必须通过 Activity。
```

---

## 9. 数据与证据技术清单

### 9.1 核心表

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

### 9.2 不可变对象

```text
EvidenceDossier；
AdjudicationDraft；
DeliberationReport；
ReviewPacket；
RemedyPlan；
HumanReviewRecord；
ActionRecord。
```

### 9.3 证据存储原则

```text
原件在 MinIO；
元数据在 PostgreSQL；
检索投影在 Elasticsearch；
权限在 Java API Service；
下载使用短期签名 URL；
摘要可回溯原件；
解析失败仍保留原件。
```

---

## 10. 工具权限

### 10.1 Agent 可用工具

```text
案件基础信息只读；
订单/物流/支付/售后授权只读；
聊天记录授权只读；
证据解析；
规则检索；
相似案件脱敏检索；
结构化阶段产物保存草案。
```

### 10.2 Agent 禁用工具

```text
refund.execute；
replacement.execute；
after_sales.reject；
after_sales.close；
review.approve；
database.write；
arbitrary_http；
shell.execute；
secret.read。
```

### 10.3 Tool Executor 可用

仅开放已注册、强类型、可审计的业务动作适配器，并要求：

```text
approved review record；
approved action hash；
valid reviewer authority；
idempotency key；
non-expired approval；
complete audit context。
```

---

## 11. 安全、审计与可观测性技术清单

### 11.1 安全

```text
RBAC + 数据范围；
服务身份隔离；
Agent Profile 最小权限；
Prompt 注入隔离；
敏感字段脱敏；
短期对象签名 URL；
审批与执行职责分离；
动作 hash；
Redis 锁 + 数据库唯一键双重幂等。
```

### 11.2 审计

必须记录：

```text
身份访问；
案件状态；
证据版本；
Agent 输入引用；
Agent 工具调用；
Agent 输出；
Profile/Prompt/Skill/模型/规则版本；
评议团异议；
人审理由；
执行请求、响应和外部流水号。
```

### 11.3 观测指标

```text
Workflow 成功率；
Workflow 等待时长；
Agent Schema 通过率；
Agent 引用错误率；
Agent 预算超限率；
工具调用成功率；
补证次数和有效率；
人审接受/修改/退回/拒绝率；
执行成功率；
结果未知率；
模型 Token、成本和时延；
相似案件一致性；
公平性指标。
```

---

## 12. 配置与部署技术清单

### 12.1 统一模型配置

```text
DEFAULT_LLM_MODEL=qwen3.7-plus
LITELLM_DEFAULT_MODEL=qwen3.7-plus
AGENT_LLM_MODEL=qwen3.7-plus
EVALUATION_LLM_MODEL=qwen3.7-plus
```

所有 LLM 调用必须经过 LiteLLM。

### 12.2 Docker Compose 服务

```text
postgresql；
redis；
elasticsearch；
minio；
temporal-server；
langfuse；
litellm-proxy；
java-api-service；
python-agent-service；
ocr-parser-service；
frontend；
nginx。
```

如所选组件官方镜像需要附属容器，可自动补齐，但不得引入与本项目无关的新中间件。

### 12.3 禁止第一版引入

```text
Kafka；
MCP；
Milvus；
Qdrant；
OPA；
Drools；
Kubernetes；
服务网格；
自部署大模型；
每个 Agent 单独一个微服务。
```

---

## 13. 测试与质量门禁

### 13.1 必测范围

```text
Router 三路径；
C1-C6、三轮陈述、最终裁决方案收敛；
Critic 部分失败和重大异议；
ReviewPacket 过期；
未审批执行拒绝；
action_hash 篡改；
重复退款/补发幂等；
Prompt 注入；
越权案件访问；
原件/解析/摘要区分；
Agent Schema 校验；
证据引用校验；
规则引用校验。
```

### 13.2 Agent 发布门禁

以下变更必须通过固定数据集回放、安全测试、偏差分析和人工抽检：

```text
模型；
Prompt；
Skill；
Profile；
规则；
Schema；
Guardrail；
Tool Gateway。
```

---

## 14. 企业级非功能技术要求

### 14.1 性能

```text
争议接待官常规响应 P95 <= 5s；
小型案件证据书记官初版卷宗生成 P95 <= 30s；
AI 主审官单 Stage P95 <= 20s；
AI 评议团 P95 <= 60s 或异步展示；
审核台 ReviewPacket 查询 P95 <= 2s。
```

### 14.2 可用性

```text
Agent 不可用时可转人工；
评议团不可用时不能绕过人审；
OCR 不可用时保留原件；
Langfuse 不可用不阻塞主流程；
LiteLLM 不可用不得跳过人审或执行。
```

### 14.3 成本控制

```text
每个 Agent 有 token budget；
上下文按需检索；
评议团按风险触发；
重复证据和大附件摘要化；
Evaluation Agent 离线批处理。
```

---

## 15. 技术验收清单

- [ ] 产品入口只面向履约争端。
- [ ] 非争端请求转交后结束。
- [ ] `FulfillmentDisputeCase` 是唯一主对象。
- [ ] API 不使用 `/v2`、`/v3` 路径。
- [ ] 三路径和五个 Workflow 定义一致。
- [ ] 六类 Agent 及其权限边界完整。
- [ ] Agent Harness 十二项能力完整。
- [ ] AI 主审官只生成非最终草案。
- [ ] AI 评议团只生成质询报告。
- [ ] 审核辅助官不能审批。
- [ ] Evaluation Agent 仅离线运行。
- [ ] 所有动作必须平台人审。
- [ ] Tool Executor 校验审批、权限、hash 和幂等。
- [ ] 证据、规则和 Agent 运行可追踪。
- [ ] PostgreSQL/MinIO/Elasticsearch/Redis 边界清晰。
- [ ] Frontend 是 AI Native 争端审理工作空间，不是订单中心。
- [ ] Docker 和配置基线未因文档改造发生不必要变化。

---

## 16. 最终定版

最终技术方案继续使用 Java、Python、Temporal、PostgreSQL、Redis、MinIO、Elasticsearch、LiteLLM、Langfuse、Vue 和 Docker Compose 基线，但产品与工程中心从泛履约协作收敛为履约争端案件审理。

一句话定版：

```text
以 Temporal 控制可靠流程，以 Agent Runtime Harness 约束 AI 认知协作，以 AI Native 前端呈现证据与审理过程，以平台人审承担最终责任，以 Tool Executor 完成确定性、幂等、可审计执行。
```

---

## 17. 房间式数字人协作技术清单

### 17.1 Java 新增模块

- [ ] `casecore` 支持 `EXTERNAL_IMPORT / INTAKE_CREATED` 和幂等外部导入。
- [ ] `casecore` 输出争议办理总览投影，不查询普通订单。
- [ ] `room` 管理 `INTAKE / EVIDENCE / HEARING / REVIEW`。
- [ ] `room` 保存不可变消息、参与方和服务端时钟投影。
- [ ] `room` 提供带 `Last-Event-ID` 的 SSE。
- [ ] `notification` 使用事务 Outbox 生成传票信箱。
- [ ] `evidence` 实现五级可信度和双方举证完成。
- [ ] `hearing` 实现三轮、和解版本与双方确认。

### 17.2 数据表

- [ ] `case_participant`
- [ ] `case_room`
- [ ] `room_message`
- [ ] `case_phase_clock`
- [ ] `evidence_verification`
- [ ] `evidence_party_completion`
- [ ] `hearing_round`
- [ ] `settlement_proposal`
- [ ] `settlement_confirmation`
- [ ] `notification`
- [ ] `notification_outbox`
- [ ] `case_timeline_event` 有单调序号、房间、受众和事件键。

### 17.3 时效

- [ ] `EVIDENCE_WINDOW=PT2H`。
- [ ] 双方提前完成可以提前封卷。
- [ ] 举证到期自动封卷并开放小法庭。
- [ ] `HEARING_WINDOW=PT3H`。
- [ ] `HEARING_PARTY_STAGE_WINDOW=PT20M`。
- [ ] 补证不重置庭审时钟。
- [ ] 双方回答和双方补证分别使用一个共享绝对截止时间，不再按固定轮次推进。
- [ ] 所有时钟由 Temporal 驱动，前端只是投影。

### 17.4 证据

- [ ] 双方共享证据目录。
- [ ] 原件按 `PARTIES / PRIVATE / PLATFORM` 授权。
- [ ] 可信度为 `VERIFIED / PLAUSIBLE / SUSPICIOUS / REJECTED / NEEDS_HUMAN_REVIEW`。
- [ ] 没有确定性来源证明时，AI 不得输出 `VERIFIED`。
- [ ] 隔离材料可审计但不进入冻结卷宗。

### 17.5 庭审与评议

- [ ] 用户和商家可以完成三轮陈述、解释证据和确认和解。
- [ ] 每轮双方各有一次“提交本轮”动作，双方都提交或 20 分钟到期后自动封存本轮。
- [ ] `FACTS_SUFFICIENT` 只作为置信度信号，不提前终止三轮陈述。
- [ ] 前三轮 AI 主审官只输出争点归纳、证据解释、风险信号和下一轮问题。
- [ ] 第三轮结束或 PT3H 到期后必须生成确定的非最终裁决执行方案草案。
- [ ] 审核员在 ReviewPacket 冻结前只读。
- [ ] `settlement_proposal` 版本变化使旧确认失效。
- [ ] 双方确认同一当前版本才视为达成一致。
- [ ] AI 评审团只在最终方案生成后介入，不在每轮陈述后介入。
- [ ] 高风险、低置信度、重大冲突、规则不确定或 Guardrail 要求时触发最终评审团。
- [ ] 评审团阈值为 80/100，单轮最多要求 AI 主审官重生成 2 次。
- [ ] 跳过评审团仍强制平台人审。

### 17.6 传票信箱

- [ ] 只做平台站内通知，不依赖外部短信。
- [ ] 覆盖受理、传票、举证、开庭、补证、和解、终审和执行事件。
- [ ] 通知按角色脱敏。
- [ ] 点击通知进入授权案件房间。
- [ ] `(business_event_key, recipient_id)` 幂等。

### 17.7 前端与 Figma

- [ ] 总览为左侧争议订单栏 + 右侧审理游园。
- [ ] 有争议接待室。
- [ ] 有证据书记官室。
- [ ] 有共享小法庭。
- [ ] 有审核员专属终审视图。
- [ ] 有结果页和传票信箱。
- [ ] 数字人为 2D 卡通，支持七种运行状态。
- [ ] 用户和商家看不到审核辅助官。
- [ ] 所有页面提供减少动画模式。
- [ ] Vue 实现已与具体 Figma 节点截图对比。
