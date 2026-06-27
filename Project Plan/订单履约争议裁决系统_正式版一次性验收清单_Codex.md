# 订单履约争议裁决系统：正式版一次性验收清单（Codex 对照验收版）

> 适用文档：`订单履约争议裁决系统_正式版开发文档_Codex主控.md`  
> 验收目标：让 Codex 在完成开发后，按照本文档一次性对照验收整个正式版项目，确认是否完整覆盖主控开发文档的全部章节、全部模块、全部技术栈、全部工程规范和全部禁止事项。  
> 验收原则：不做 MVP、不做简化版、不只验收核心链路、不跳过测试、不绕过 Human-in-the-loop、不允许 Agent 直接裁决或执行。

---

## 0. 验收使用说明

### 0.1 使用对象

本文档用于以下场景：

- Codex 完成阶段性开发后进行自检。
- Codex 完成全项目开发后进行一次性总体验收。
- 人工 Code Review 前让 Codex 先输出验收报告。
- 项目交付前对照主控开发文档检查是否遗漏模块。
- 后续迭代时检查是否破坏既有架构边界。

### 0.2 验收方式

Codex 必须按以下顺序执行验收：

1. 读取主控开发文档。
2. 扫描当前仓库目录、代码、配置、测试、部署文件。
3. 按本文档所有 Checklist 项逐项检查。
4. 每一项必须给出验收状态。
5. 每一项必须给出证据位置。
6. 所有失败项必须给出原因和修复建议。
7. 最终输出总体验收结论。

### 0.3 验收状态定义

| 状态 | 含义 | 使用条件 |
|---|---|---|
| PASS | 通过 | 已完整实现，且有代码、测试或文档证据 |
| FAIL | 未通过 | 未实现、实现错误、违反主控文档或无证据 |
| PARTIAL | 部分通过 | 有实现但不完整，或缺少测试 / 文档 / 部署验证 |
| BLOCKED | 阻塞 | 因缺少外部配置、密钥、依赖、业务接口等无法验证 |
| N/A | 不适用 | 主控文档明确当前版本不做该项 |

### 0.4 证据填写规范

每一项验收都必须写明证据位置，格式如下：

```text
状态：PASS / FAIL / PARTIAL / BLOCKED / N/A
证据：文件路径 + 类名 / 方法名 / 配置项 / 测试用例 / 命令输出
说明：为什么通过或不通过
修复建议：仅 FAIL / PARTIAL / BLOCKED 需要填写
```

示例：

```text
状态：PASS
证据：java-api-service/src/main/java/.../modules/executor/application/ToolExecutorService.java#executeApprovedActions
说明：Tool Executor 执行前校验 approval_record.approved_for_execution=true，并校验 idempotency_key。
修复建议：无
```

---

## 1. 一票否决项

以下任意一项为 FAIL，则本项目不得判定为正式版验收通过。

| 编号 | 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| VETO-01 | 是否存在 Agent 直接作为最终法官、直接输出最终裁决的实现 |  |  |  |
| VETO-02 | 是否存在 C 层直接执行退款、补发、关闭售后的实现 |  |  |  |
| VETO-03 | 是否存在 D 层重新断案、推翻 C 层事实认定的实现 |  |  |  |
| VETO-04 | 是否存在 Tool Executor 执行未审批动作的路径 |  |  |  |
| VETO-05 | 是否绕过 Platform Human Review 直接执行影响用户权益或商家成本的动作 |  |  |  |
| VETO-06 | 是否把正式版降级为 MVP / Demo / 只跑核心链路 |  |  |  |
| VETO-07 | 是否遗漏 A/B/Router/C/D/Approval/Human Review/Tool Executor/Case Closure/Evaluation 任一核心模块 |  |  |  |
| VETO-08 | 是否引入主控文档明确暂不采用的技术：MCP、Kafka、Milvus、Qdrant、Kubernetes、K3s、Helm、OPA、Drools、vLLM、A2A、CrewAI、完整微服务拆分 |  |  |  |
| VETO-09 | 是否设计或实现申诉 / 复审流程 |  |  |  |
| VETO-10 | 是否存在核心业务表、审计表、执行记录表缺失导致无法追溯 |  |  |  |
| VETO-11 | 是否存在敏感密钥提交到仓库或日志打印密钥 |  |  |  |
| VETO-12 | 是否存在全量测试无法运行或大量测试被跳过 |  |  |  |
| VETO-13 | Docker Compose 是否无法启动核心服务 |  |  |  |
| VETO-14 | 是否没有任何端到端流程能够跑通 |  |  |  |

---

## 2. 主控文档章节覆盖验收

Codex 必须确认主控开发文档 21 个章节的要求均已在代码、配置、测试或文档中落地。

| 章节 | 主控文档内容 | 验收要求 | 状态 | 证据 | 说明 |
|---|---|---|---|---|---|
| 1 | 文档说明与最高优先级约束 | 项目 README / docs 中保留正式版约束，不允许 MVP 化 |  |  |  |
| 2 | 项目背景与建设目标 | README 或 docs 说明系统定位、业务价值、正式版范围 |  |  |  |
| 3 | 总体架构理解 | 服务拆分、调用链路、Java/Python 边界、数据流有实现和文档 |  |  |  |
| 4 | 正式版功能范围 | 4.1-4.14 所有模块均存在实现或明确接口 |  |  |  |
| 5 | 仓库结构设计 | monorepo 结构符合文档要求 |  |  |  |
| 6 | 后端工程结构规范 | Java 后端分层符合 controller/application/domain/infrastructure |  |  |  |
| 7 | Agent / Workflow / Tool 工程设计 | Agent、Temporal、LangGraph、Tool API、Trace、HITL 均落地 |  |  |  |
| 8 | API 接口设计规范 | REST API、统一响应、错误码、OpenAPI、幂等、鉴权均落地 |  |  |  |
| 9 | 数据库与数据模型设计 | PostgreSQL 表、ES 索引、Redis Key、MinIO Bucket 均落地 |  |  |  |
| 10 | 核心业务流程设计 | 普通履约、明确规则、争议审理三条 E2E 流程均可跑通 |  |  |  |
| 11 | 配置管理规范 | local/dev/test/uat/prod、env、敏感配置、Feature Flag 均落地 |  |  |  |
| 12 | 异常处理与错误码规范 | 统一异常体系、错误码、外部服务异常、Agent/Tool 异常处理落地 |  |  |  |
| 13 | 日志、Trace 与可观测性规范 | trace_id、request_id、case_id、Langfuse、审计、指标均落地 |  |  |  |
| 14 | 安全设计规范 | 角色权限、API 安全、Prompt 注入防护、Tool 权限、审计日志落地 |  |  |  |
| 15 | 代码规范 | 命名、包结构、职责边界、DTO/Entity 转换、枚举、禁止事项达标 |  |  |  |
| 16 | 测试方案 | 单元、集成、API、Workflow、Agent、Tool、权限、回归、性能、部署测试覆盖 |  |  |  |
| 17 | 部署方案 | Docker Compose、启动顺序、端口、初始化、健康检查、日志挂载落地 |  |  |  |
| 18 | CI/CD 与质量保障 | 分支、Commit、PR、Review、自动化检查、构建、回滚文档或配置落地 |  |  |  |
| 19 | Codex 开发任务拆解 | Phase 1-16 的输出物均可在仓库中定位 |  |  |  |
| 20 | Codex 执行提示词模板 | docs/codex 中保留可复制提示词或等价文档 |  |  |  |
| 21 | 最终验收清单 | 项目具备完整验收 checklist，并能输出验收报告 |  |  |  |

---

## 3. 服务与技术栈验收

### 3.1 Docker Compose 服务完整性

| 服务 | 技术栈 | 必须存在内容 | 状态 | 证据 | 说明 |
|---|---|---|---|---|---|
| frontend | Vue3 + Vite + Element Plus | 前端工程、Dockerfile、Nginx 代理、页面路由 |  |  |  |
| java-api-service | Java 21 + Spring Boot 3 | 主业务后端、API、Tool API、Temporal Worker |  |  |  |
| temporal-server | Temporal + Java SDK | Docker 服务、Java Workflow 对接 |  |  |  |
| python-agent-service | Python FastAPI + LangGraph | Agent API、C1-C6、LangGraph、LiteLLM、Langfuse |  |  |  |
| litellm-proxy | LiteLLM Proxy | Docker 服务、模型配置、Python Agent 调用配置 |  |  |  |
| langfuse | Langfuse | Docker 服务、Agent Trace 写入 |  |  |  |
| postgresql | PostgreSQL | Docker 服务、migration、数据卷 |  |  |  |
| redis | Redis | Docker 服务、连接配置、限流/幂等/缓存使用 |  |  |  |
| elasticsearch | Elasticsearch | Docker 服务、index template、证据/政策/历史 case 检索 |  |  |  |
| minio | MinIO | Docker 服务、bucket 初始化、文件上传 |  |  |  |
| ocr-parser-service | Python + PaddleOCR + MarkItDown | OCR API、文件解析、MinIO/ES 对接 |  |  |  |
| nginx | Nginx | 统一代理 frontend/java/python/ocr/langfuse/litellm |  |  |  |

### 3.2 暂不采用技术验收

Codex 必须扫描依赖、配置、Docker Compose、代码引用，确认以下技术未作为当前版本依赖引入。

| 技术 | 当前版本要求 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| Kubernetes / K3s / Helm | 不采用 |  |  |  |
| Milvus / Qdrant | 不采用 |  |  |  |
| MCP | 不采用 |  |  |  |
| OPA / Drools | 不采用 |  |  |  |
| Kafka | 不采用 |  |  |  |
| vLLM / 自部署大模型 | 不采用 |  |  |  |
| A2A / CrewAI | 不采用 |  |  |  |
| 完整微服务拆分 | 不采用 |  |  |  |

---

## 4. 仓库结构验收

| 目录 / 文件 | 验收要求 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| `README.md` | 说明项目定位、启动方式、服务边界 |  |  |  |
| `CONTRIBUTING.md` | 说明协作规范 |  |  |  |
| `CODE_STYLE.md` | 说明代码规范 |  |  |  |
| `SECURITY.md` | 说明安全原则和密钥管理 |  |  |  |
| `.env.example` | 提供无真实密钥的环境变量模板 |  |  |  |
| `docker-compose.yml` | 包含全部服务 |  |  |  |
| `docs/architecture` | 架构、模块边界、Workflow 文档 |  |  |  |
| `docs/api` | OpenAPI 与错误码文档 |  |  |  |
| `docs/database` | 表设计与迁移文档 |  |  |  |
| `docs/deployment` | Docker Compose 部署文档 |  |  |  |
| `docs/codex` | Codex 开发计划、提示词、Review Checklist |  |  |  |
| `deploy/nginx` | Nginx 配置 |  |  |  |
| `deploy/postgresql` | PostgreSQL 初始化配置 |  |  |  |
| `deploy/elasticsearch` | ES index template |  |  |  |
| `deploy/minio` | bucket 初始化脚本 |  |  |  |
| `deploy/litellm` | LiteLLM 配置 |  |  |  |
| `scripts` | dev-up/dev-down/dev-reset/init/smoke-test 脚本 |  |  |  |
| `frontend` | Vue3 工程完整 |  |  |  |
| `java-api-service` | Spring Boot 工程完整 |  |  |  |
| `python-agent-service` | FastAPI + LangGraph 工程完整 |  |  |  |
| `ocr-parser-service` | OCR / 文件解析工程完整 |  |  |  |
| `tests` | API/E2E/load/fixtures 测试目录完整 |  |  |  |

---

## 5. Java 后端分层验收

### 5.1 通用工程能力

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| Java 版本为 Java 21 |  |  |  |
| Spring Boot 版本为 3.x |  |  |  |
| Maven 构建可运行 |  |  |  |
| 存在统一 `ApiResponse` |  |  |  |
| 存在统一 `ErrorCode` |  |  |  |
| 存在统一 `BusinessException` |  |  |  |
| 存在 `GlobalExceptionHandler` |  |  |  |
| 存在 `TraceIdFilter` 或等价 Trace 拦截器 |  |  |  |
| 存在审计日志切面或审计服务 |  |  |  |
| 存在幂等服务 |  |  |  |
| 存在统一权限模型 |  |  |  |
| 存在 OpenAPI / Swagger 配置 |  |  |  |
| 存在 Actuator Health Check |  |  |  |

### 5.2 分层边界

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| Controller 只做请求接收、参数校验、调用 Application Service |  |  |  |
| Controller 不直接访问 Repository / Mapper |  |  |  |
| Controller 不直接调用 Python Agent / OCR / MinIO / ES |  |  |  |
| Application 层负责编排业务用例 |  |  |  |
| Domain 层包含领域模型、枚举、状态机、领域规则 |  |  |  |
| Infrastructure 层包含 Repository、Mapper、外部系统 Adapter |  |  |  |
| Entity / DO 不直接返回前端 |  |  |  |
| DTO / VO / Command / Domain Model 边界清晰 |  |  |  |
| 外部 API 调用统一在 integration/client 层 |  |  |  |
| 事务边界不跨远程 Agent / OCR / Tool 调用 |  |  |  |

### 5.3 Java 模块完整性

| 模块 | 必须存在内容 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| `casecore` | case 创建、查询、状态流转 |  |  |  |
| `intake` | Intake 编排、缺槽处理、Agent 对接 |  |  |  |
| `evidence` | 证据上传、卷宗、时间线、矩阵、OCR 触发 |  |  |  |
| `router` | 三路径路由决策 |  |  |  |
| `regularflow` | 普通履约处理结论 |  |  |  |
| `ruleflow` | 明确规则处理结论 |  |  |  |
| `hearing` | Hearing State、Record、C 层结果落库 |  |  |  |
| `workflow` | Temporal Workflow、Activities、Signals、Queries |  |  |  |
| `remedy` | Remedy Plan 生成 |  |  |  |
| `approval` | 审批规则、审批路径 |  |  |  |
| `review` | Review Task、Review Packet、审核动作 |  |  |  |
| `executor` | Tool Executor、审批校验、幂等执行 |  |  |  |
| `evaluation` | Evaluation Trace、评估结果查询 |  |  |  |
| `policy` | 政策版本、政策查询、规则配置 |  |  |  |
| `tool` | 订单/物流/售后/仓储/消息/执行模拟 Tool API |  |  |  |
| `audit` | 审计日志记录与查询 |  |  |  |

---

## 6. Python Agent Service 验收

### 6.1 服务基础

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| 使用 FastAPI |  |  |  |
| 使用 LangGraph 实现 C 层流程 |  |  |  |
| 使用 Pydantic 定义输入输出 schema |  |  |  |
| 使用 LiteLLM Proxy 统一模型调用 |  |  |  |
| 使用 Langfuse 记录 Agent Trace |  |  |  |
| 提供 `/health` |  |  |  |
| 提供 Intake API |  |  |  |
| 提供 Hearing API |  |  |  |
| 提供 Evaluation API |  |  |  |
| 具备超时、异常、schema 校验失败处理 |  |  |  |

### 6.2 Agent 节点完整性

| Agent | 验收要求 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| Case Intake Agent | 输出 case 类型、售后类型、争议类型、诉求、缺失信息、风险等级 |  |  |  |
| C1 Issue Framing Agent | 输出 Issue List、required evidence、issue type、状态、优先级 |  |  |  |
| C2 Evidence Gap & Request Agent | 输出 Evidence Request List、补证请求、材料类型、截止时间 |  |  |  |
| C3 Party Liaison Agent | 输出中立联络内容、Party Submission 结构化结果 |  |  |  |
| C4 Evidence Cross-check Agent | 输出支持证据、冲突证据、缺失证据、人工核验点 |  |  |  |
| C5 Rule Application Agent | 输出政策、版本、满足条件、不满足条件、待证条件、适用理由 |  |  |  |
| C6 Adjudication Draft Agent | 输出事实认定、证据评价、规则适用、裁决建议、置信度、审核重点 |  |  |  |
| Evaluation Agent | 只分析 closed case，输出评估报告、指标、规则缺口、改进建议 |  |  |  |

### 6.3 Agent 禁止行为验收

| 禁止项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| Agent 不得输出最终裁决表述 |  |  |  |
| Agent 不得直接调用退款、补发、关闭售后工具 |  |  |  |
| Agent 不得绕过 Platform Human Review |  |  |  |
| Agent 不得承诺退款 / 补发 / 驳回 |  |  |  |
| Agent 不得泄露一方隐私给另一方 |  |  |  |
| Agent 不得引用不存在的政策 |  |  |  |
| Agent 不得用常识替代平台规则 |  |  |  |
| Evaluation Agent 不得干预在线 case |  |  |  |

---

## 7. Temporal Workflow 验收

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| 存在 `CaseFulfillmentDisputeWorkflow` 或等价 Workflow |  |  |  |
| Workflow 覆盖 CASE_CREATED 到 CASE_CLOSED 主要状态 |  |  |  |
| Workflow 可启动 |  |  |  |
| Workflow 可查询当前状态 |  |  |  |
| Workflow 可等待用户补证 |  |  |  |
| Workflow 可等待商家补证 |  |  |  |
| Workflow 可等待平台审核员确认 |  |  |  |
| Workflow 支持用户补证 Signal |  |  |  |
| Workflow 支持商家补证 Signal |  |  |  |
| Workflow 支持审核员决策 Signal |  |  |  |
| Workflow 支持补证超时 |  |  |  |
| Workflow 支持人审超时或提醒 |  |  |  |
| Workflow Activity 有重试策略 |  |  |  |
| Workflow 失败可恢复或进入人工重点审核 |  |  |  |
| Hearing State 持久化到 PostgreSQL |  |  |  |
| Hearing Record 持久化到 PostgreSQL |  |  |  |

---

## 8. Tool API 与 Tool Executor 验收

### 8.1 Tool API 类型完整性

| Tool 类型 | 必须能力 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| 订单工具 | 查询订单状态、支付状态、商品信息、订单金额 |  |  |  |
| 物流工具 | 查询物流轨迹、签收证明、异常节点、物流时效 |  |  |  |
| 售后工具 | 查询售后申请、退货申请、退款状态、售后历史 |  |  |  |
| 仓储工具 | 查询出库记录、发货记录、退货验收、质检记录 |  |  |  |
| 证据工具 | 上传证据、归档、元数据提取、脱敏 |  |  |  |
| 政策工具 | 查询平台政策、商家政策、政策版本、类目规则 |  |  |  |
| 消息工具 | 通知用户、通知商家、补证提醒、审核结果通知 |  |  |  |
| 审批工具 | 创建审核单、记录审核动作、保存审核意见 |  |  |  |
| 执行工具 | 退款、补发、关闭售后、驳回申请、创建工单 |  |  |  |

### 8.2 Tool Executor 安全边界

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| Tool Executor 只接收 Approved Remedy Plan |  |  |  |
| 执行前校验 Approval Record |  |  |  |
| 执行前校验 action 在已审批计划内 |  |  |  |
| 执行前校验 case 状态允许执行 |  |  |  |
| 执行前校验幂等键 |  |  |  |
| 未审批退款被拒绝 |  |  |  |
| 未审批补发被拒绝 |  |  |  |
| 未审批关闭售后被拒绝 |  |  |  |
| 重复执行不会重复退款 / 补发 / 通知 |  |  |  |
| 执行失败写入 Action Record |  |  |  |
| 执行成功更新 case 状态或动作状态 |  |  |  |

---

## 9. API 一次性验收

### 9.1 API 规范

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| API 前缀统一为 `/api/v1` |  |  |  |
| Python Agent API 前缀统一 |  |  |  |
| OCR API 前缀统一 |  |  |  |
| 成功响应使用统一结构 |  |  |  |
| 错误响应使用统一结构 |  |  |  |
| 响应包含 request_id、trace_id、timestamp |  |  |  |
| 分页接口格式统一 |  |  |  |
| 写操作支持 Idempotency-Key |  |  |  |
| API 有 OpenAPI / Swagger 文档 |  |  |  |
| API 有鉴权与权限校验 |  |  |  |

### 9.2 核心 API 清单

| API | 必须存在 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| `POST /api/v1/cases` | 创建 case |  |  |  |
| `GET /api/v1/cases/{case_id}` | 查询 case |  |  |  |
| `POST /api/v1/cases/{case_id}/dossier/build` | 构建 dossier |  |  |  |
| `GET /api/v1/cases/{case_id}/dossier` | 查询 dossier |  |  |  |
| `POST /api/v1/cases/{case_id}/evidences` | 上传证据 |  |  |  |
| `POST /api/v1/cases/{case_id}/route` | 路由 case |  |  |  |
| `POST /api/v1/cases/{case_id}/workflow/start` | 启动 workflow |  |  |  |
| `GET /api/v1/cases/{case_id}/hearing` | 查询 hearing |  |  |  |
| `POST /api/v1/cases/{case_id}/submissions/user` | 用户补证 |  |  |  |
| `POST /api/v1/cases/{case_id}/submissions/merchant` | 商家补证 |  |  |  |
| `GET /api/v1/cases/{case_id}/adjudication-draft` | 查询裁决草案 |  |  |  |
| `GET /api/v1/cases/{case_id}/remedy-plan` | 查询 Remedy Plan |  |  |  |
| `GET /api/v1/review-tasks` | 审核任务列表 |  |  |  |
| `GET /api/v1/review-tasks/{task_id}/packet` | 查询审核包 |  |  |  |
| `POST /api/v1/review-tasks/{task_id}/decision` | 提交审核结果 |  |  |  |
| `POST /api/v1/cases/{case_id}/execution/execute` | 执行审批动作 |  |  |  |
| `GET /api/v1/cases/{case_id}/actions` | 查询执行记录 |  |  |  |
| `POST /api/v1/cases/{case_id}/close` | 关闭 case |  |  |  |
| `GET /api/v1/cases/{case_id}/evaluation` | 查询评估报告 |  |  |  |
| `POST /agent-api/v1/intake/analyze` | Intake Agent |  |  |  |
| `POST /agent-api/v1/hearings/analyze` | Hearing Agent |  |  |  |
| `POST /agent-api/v1/evaluations/analyze` | Evaluation Agent |  |  |  |
| `POST /ocr-api/v1/parse-tasks` | 创建解析任务 |  |  |  |
| `GET /ocr-api/v1/parse-tasks/{task_id}` | 查询解析结果 |  |  |  |

---

## 10. 数据库、缓存、对象存储、检索验收

### 10.1 PostgreSQL 表验收

| 表 | 必须存在 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| `fulfillment_case` | case 主表 |  |  |  |
| `evidence_dossier` | 证据卷宗 |  |  |  |
| `evidence_item` | 证据项 |  |  |  |
| `party_claim` | 双方主张 |  |  |  |
| `issue` | 争点 |  |  |  |
| `claim_issue_evidence_matrix` | 主张-争点-证据矩阵 |  |  |  |
| `evidence_request` | 补证请求 |  |  |  |
| `party_submission` | 补证提交 |  |  |  |
| `hearing_state` | 审理状态 |  |  |  |
| `hearing_record` | 审理记录 |  |  |  |
| `adjudication_draft` | 裁决草案 |  |  |  |
| `remedy_plan` | 执行方案 |  |  |  |
| `review_packet` | 审核包 |  |  |  |
| `review_task` | 审核任务 |  |  |  |
| `approval_record` | 审批记录 |  |  |  |
| `action_record` | 执行动作记录 |  |  |  |
| `audit_log` | 审计日志 |  |  |  |
| `policy_rule` | 政策规则 |  |  |  |
| `evaluation_trace` | 评估轨迹 |  |  |  |

### 10.2 数据库规范验收

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| 所有核心表包含主键 |  |  |  |
| 关键业务表包含 created_at / updated_at |  |  |  |
| 需要审计的表包含 created_by / updated_by 或审计关联 |  |  |  |
| 状态字段使用枚举字符串 |  |  |  |
| 金额字段使用 numeric 类型 |  |  |  |
| JSON 结构字段使用 jsonb |  |  |  |
| 高频查询字段有索引 |  |  |  |
| 幂等键有唯一约束 |  |  |  |
| Flyway migration 可在空库执行 |  |  |  |
| 不存在业务核心数据只存 Redis 的情况 |  |  |  |

### 10.3 Elasticsearch 验收

| 索引 | 用途 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| `policy_index` | 政策检索 |  |  |  |
| `evidence_index` | 证据文本检索 |  |  |  |
| `case_index` | 历史 case 检索 |  |  |  |

### 10.4 Redis 验收

| Key 类型 | 用途 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| `dispute:session:{session_id}` | 会话缓存 |  |  |  |
| `dispute:case:{case_id}:state` | 短期状态 |  |  |  |
| `dispute:lock:{idempotency_key}` | 幂等锁 |  |  |  |
| `dispute:rate-limit:{user_id}:{api}` | 限流 |  |  |  |
| `dispute:agent-context:{case_id}` | Agent 短期上下文 |  |  |  |

### 10.5 MinIO 验收

| Bucket | 用途 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| `evidence-original` | 原始证据文件 |  |  |  |
| `evidence-desensitized` | 脱敏证据文件 |  |  |  |
| `ocr-temp` | OCR 临时文件 |  |  |  |
| `policy-files` | 政策文件 |  |  |  |
| `review-exports` | 审核导出文件 |  |  |  |

---

## 11. 核心业务流程 E2E 验收

### 11.1 普通履约流

| 步骤 | 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| 1 | 用户发起查物流 / 催发货 case |  |  |  |
| 2 | Case Intake Agent 识别为普通履约问题 |  |  |  |
| 3 | Evidence Dossier Builder 收集订单和物流证据 |  |  |  |
| 4 | Dispute Router 路由到 `REGULAR_FULFILLMENT` |  |  |  |
| 5 | Regular Flow 生成普通处理结论 |  |  |  |
| 6 | Remedy Planner 生成通知 / 催办 / 工单方案 |  |  |  |
| 7 | Approval Policy Engine 生成审批路径 |  |  |  |
| 8 | Platform Human Review 确认 |  |  |  |
| 9 | Tool Executor 执行通知 / 催办 / 工单 |  |  |  |
| 10 | Case Closure 关闭 case |  |  |  |
| 11 | Evaluation Trace 准备完成 |  |  |  |

### 11.2 明确规则流

| 步骤 | 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| 1 | 用户或商家发起明确规则售后 |  |  |  |
| 2 | Case Intake Agent 识别售后诉求 |  |  |  |
| 3 | Evidence Dossier Builder 收集平台证据 |  |  |  |
| 4 | Dispute Router 路由到 `RULE_BASED_RESOLUTION` |  |  |  |
| 5 | Rule Flow 匹配政策规则和版本 |  |  |  |
| 6 | 生成规则处理结论 |  |  |  |
| 7 | Remedy Planner 生成退款 / 驳回 / 取消 / 通知方案 |  |  |  |
| 8 | Approval Policy Engine 判断风险和审批路径 |  |  |  |
| 9 | Platform Human Review 确认方案 |  |  |  |
| 10 | Tool Executor 执行审批动作 |  |  |  |
| 11 | Case Closure 关闭 case |  |  |  |

### 11.3 争议审理流

| 步骤 | 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| 1 | 用户或商家发起争议 |  |  |  |
| 2 | Case Intake Agent 识别纠纷类型和双方主张 |  |  |  |
| 3 | Evidence Dossier Builder 构建证据卷宗 |  |  |  |
| 4 | Dispute Router 路由到 `DISPUTE_HEARING` |  |  |  |
| 5 | C0 Hearing Controller 创建 Hearing State |  |  |  |
| 6 | C1 归纳争点 |  |  |  |
| 7 | C2 识别证据缺口并生成补证请求 |  |  |  |
| 8 | C3 中立联络用户和商家 |  |  |  |
| 9 | Temporal 暂停等待用户 / 商家补证 |  |  |  |
| 10 | 用户 / 商家提交补证 |  |  |  |
| 11 | B 更新 Evidence Dossier 版本 |  |  |  |
| 12 | C4 进行证据交叉检查 |  |  |  |
| 13 | C0 判断是否继续补证循环 |  |  |  |
| 14 | C5 适用平台规则 |  |  |  |
| 15 | C6 生成裁决草案与审核包 |  |  |  |
| 16 | D 生成 Remedy Plan |  |  |  |
| 17 | Approval Policy Engine 生成审批路径 |  |  |  |
| 18 | Platform Human Review 确认 / 修改 / 驳回 / 要求补证 |  |  |  |
| 19 | 审核员要求补证时能回到补证流程 |  |  |  |
| 20 | 审核员批准后 Tool Executor 执行动作 |  |  |  |
| 21 | Case Closure 关闭 case |  |  |  |
| 22 | Evaluation Agent 对 closed case 生成评估报告 |  |  |  |

### 11.4 必测业务场景

| 场景 | 验收要求 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| 用户查物流 | 走普通履约流，不进入 C，但进入 D/审批/人审/执行 |  |  |  |
| 明确超时未发货 | 走明确规则流，生成退款方案，必须人审 |  |  |  |
| 七天无理由退货但商家称掉包 | 走争议审理流，完成 C1-C6 和人审 |  |  |  |
| 用户称未收到但物流显示签收 | 走争议审理流，补证、证据交叉检查、规则适用、人审 |  |  |  |
| 商品损坏责任争议 | 走争议审理流，识别双方证据冲突 |  |  |  |
| 少件 / 错发 / 漏发争议 | 走争议审理流，生成补证请求和裁决草案 |  |  |  |
| 高价值商品退款争议 | 进入高风险人审 |  |  |  |

---

## 12. Frontend 页面验收

| 页面 / 功能 | 验收要求 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| 登录 / 角色模拟 | 支持 USER / MERCHANT / CUSTOMER_SERVICE / PLATFORM_REVIEWER / ADMIN |  |  |  |
| Case 列表 | 可按状态、类型、角色查看 case |  |  |  |
| Case 详情 | 展示基础信息、状态、路由、当前任务 |  |  |  |
| Case 时间线 | 展示订单、物流、售后、争议事件时间线 |  |  |  |
| Evidence Dossier | 展示证据卷宗、证据来源、脱敏状态 |  |  |  |
| Evidence Upload | 支持上传图片、PDF、Word、Excel 等证据 |  |  |  |
| 用户补证页 | 用户可查看补证请求并提交材料 |  |  |  |
| 商家补证页 | 商家可查看补证请求并提交材料 |  |  |  |
| 审核任务列表 | 审核员可查看待审核任务 |  |  |  |
| Review Workbench | 展示 Review Packet 全部信息 |  |  |  |
| 裁决草案展示 | 展示事实认定、证据评价、规则适用、建议、置信度、审核重点 |  |  |  |
| Remedy Plan 展示 | 展示执行动作、通知方案、风险等级、前置条件 |  |  |  |
| 审核动作 | 支持确认、修改、驳回、要求补证、转人工 |  |  |  |
| 执行记录 | 展示 Action Record |  |  |  |
| 审计日志 | 展示关键审计日志 |  |  |  |
| 权限控制 | 不同角色只能看到允许内容 |  |  |  |

---

## 13. 配置与环境验收

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| 支持 local/dev/test/uat/prod 配置 |  |  |  |
| Java 配置文件结构完整 |  |  |  |
| Python `.env` 配置结构完整 |  |  |  |
| Frontend `.env` 配置结构完整 |  |  |  |
| `.env.example` 不包含真实密钥 |  |  |  |
| 数据库连接配置可通过环境变量注入 |  |  |  |
| Redis 配置可通过环境变量注入 |  |  |  |
| MinIO 配置可通过环境变量注入 |  |  |  |
| Elasticsearch 配置可通过环境变量注入 |  |  |  |
| Agent Service URL 可配置 |  |  |  |
| OCR Service URL 可配置 |  |  |  |
| LiteLLM 配置可配置 |  |  |  |
| Langfuse 配置可配置 |  |  |  |
| Temporal 地址可配置 |  |  |  |
| Feature Flag 存在并可读取 |  |  |  |
| `feature.human-review.required` 默认开启且不可在正式验收中关闭 |  |  |  |

---

## 14. 异常处理与错误码验收

| 异常类型 | 验收要求 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| 参数异常 | 返回统一错误响应 |  |  |  |
| 鉴权异常 | 返回 401 或等价错误码 |  |  |  |
| 权限异常 | 返回 403 或等价错误码 |  |  |  |
| 资源不存在 | 返回明确 NOT_FOUND 错误码 |  |  |  |
| 业务异常 | 使用 BusinessException 或等价异常 |  |  |  |
| 幂等冲突 | 返回 IDEMPOTENCY_CONFLICT |  |  |  |
| 外部服务异常 | Agent/OCR/MinIO/ES 调用失败可识别 |  |  |  |
| 数据库异常 | 不泄露 SQL 敏感细节 |  |  |  |
| Workflow 异常 | 可记录并进入人工或失败状态 |  |  |  |
| Agent 异常 | schema 错误、超时、LLM 失败可处理 |  |  |  |
| Tool 异常 | 执行失败写 Action Record |  |  |  |
| 超时异常 | 返回或记录 EXTERNAL_SERVICE_TIMEOUT |  |  |  |
| 审批异常 | 未审批执行被拒绝 |  |  |  |

核心错误码验收：

| 错误码 | 状态 | 证据 | 说明 |
|---|---|---|---|
| `INVALID_ARGUMENT` |  |  |  |
| `UNAUTHORIZED` |  |  |  |
| `FORBIDDEN` |  |  |  |
| `CASE_NOT_FOUND` |  |  |  |
| `ORDER_NOT_FOUND` |  |  |  |
| `EVIDENCE_NOT_FOUND` |  |  |  |
| `CASE_STATUS_INVALID` |  |  |  |
| `CASE_DUPLICATED` |  |  |  |
| `IDEMPOTENCY_CONFLICT` |  |  |  |
| `EVIDENCE_UPLOAD_FAILED` |  |  |  |
| `EVIDENCE_PARSE_FAILED` |  |  |  |
| `AGENT_SERVICE_UNAVAILABLE` |  |  |  |
| `AGENT_OUTPUT_SCHEMA_INVALID` |  |  |  |
| `WORKFLOW_START_FAILED` |  |  |  |
| `WORKFLOW_SIGNAL_FAILED` |  |  |  |
| `APPROVAL_REQUIRED` |  |  |  |
| `APPROVAL_RECORD_NOT_FOUND` |  |  |  |
| `TOOL_EXECUTION_DENIED` |  |  |  |
| `TOOL_EXECUTION_FAILED` |  |  |  |
| `EXTERNAL_SERVICE_TIMEOUT` |  |  |  |

---

## 15. 日志、Trace、审计、可观测性验收

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| 所有请求生成或透传 trace_id |  |  |  |
| 所有请求生成 request_id |  |  |  |
| 日志包含 service、trace_id、request_id、case_id、workflow_id、user_id、role、action |  |  |  |
| 创建 case 有日志和 audit_log |  |  |  |
| 构建 dossier 有日志和 audit_log |  |  |  |
| 路由决策有日志和 audit_log |  |  |  |
| 每个 C 节点有 hearing_record |  |  |  |
| Agent 调用写入 Langfuse |  |  |  |
| Prompt 输入输出有 Trace |  |  |  |
| Remedy Plan 生成有日志 |  |  |  |
| 审核动作有 audit_log 和 approval_record |  |  |  |
| Tool 执行动作有 action_record |  |  |  |
| Case Closure 有 closure 记录 |  |  |  |
| 敏感信息在日志中脱敏 |  |  |  |
| 慢请求有记录或监控指标 |  |  |  |
| 关键指标有定义或实现 |  |  |  |
| 告警指标有文档或实现 |  |  |  |

---

## 16. 安全验收

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| USER 只能访问自己的 case |  |  |  |
| MERCHANT 只能访问自己相关商家 case |  |  |  |
| CUSTOMER_SERVICE 不能批准执行高风险动作 |  |  |  |
| PLATFORM_REVIEWER 可审核并批准执行 |  |  |  |
| ADMIN 可管理配置和查看评估 |  |  |  |
| SYSTEM 内部调用有服务鉴权或内部限制 |  |  |  |
| 文件上传校验类型、大小、MIME |  |  |  |
| 证据访问有权限控制 |  |  |  |
| 原始证据和脱敏证据分离 |  |  |  |
| 日志脱敏 |  |  |  |
| Prompt 注入防护落地 |  |  |  |
| Agent 输入标记用户 / 商家内容为不可信 |  |  |  |
| Agent 不接受绕过流程的用户指令 |  |  |  |
| Tool 调用权限矩阵落地 |  |  |  |
| 数据库账号最小权限或有明确文档 |  |  |  |
| `.env` 不提交真实密钥 |  |  |  |
| 审计日志不可绕过 |  |  |  |

---

## 17. 测试验收

### 17.1 测试类型覆盖

| 测试类型 | 验收要求 | 状态 | 证据 | 说明 |
|---|---|---|---|---|
| Java 单元测试 | Domain / Application / Converter / Rule / Executor 核心逻辑覆盖 |  |  |  |
| Java 集成测试 | PostgreSQL / Redis / MinIO / ES / Agent Client / OCR Client 覆盖 |  |  |  |
| API 测试 | 核心 API 正常和异常路径覆盖 |  |  |  |
| Workflow 测试 | Temporal 启动、Signal、超时、恢复覆盖 |  |  |  |
| Agent 测试 | C1-C6 schema、节点、异常覆盖 |  |  |  |
| OCR 测试 | 图片、PDF、Word、Excel 解析覆盖 |  |  |  |
| Tool 测试 | 未审批拒绝、审批执行、幂等、失败覆盖 |  |  |  |
| 权限测试 | 用户、商家、客服、审核员、管理员边界覆盖 |  |  |  |
| 回归测试 | 三条主流程和典型争议场景覆盖 |  |  |  |
| 性能测试 | P95 指标或测试脚本存在 |  |  |  |
| 部署验证测试 | Docker Compose health check 和 smoke test |  |  |  |

### 17.2 必须运行的命令

Codex 验收时必须尝试执行或检查以下命令是否可执行。

```bash
# Java
cd java-api-service && mvn test

# Python Agent
cd python-agent-service && pytest

# OCR Parser
cd ocr-parser-service && pytest

# Frontend
cd frontend && npm install && npm run test

# Docker Compose
docker compose config
docker compose up -d
./scripts/smoke-test.sh
```

| 命令 | 状态 | 输出摘要 | 失败原因 | 修复建议 |
|---|---|---|---|---|
| `mvn test` |  |  |  |  |
| `pytest` for python-agent-service |  |  |  |  |
| `pytest` for ocr-parser-service |  |  |  |  |
| `npm run test` |  |  |  |  |
| `docker compose config` |  |  |  |  |
| `docker compose up -d` |  |  |  |  |
| `./scripts/smoke-test.sh` |  |  |  |  |

---

## 18. 部署验收

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| `docker-compose.yml` 包含全部服务 |  |  |  |
| 所有应用服务有 Dockerfile |  |  |  |
| Nginx 统一代理前端、Java、Python、OCR、Langfuse、LiteLLM |  |  |  |
| PostgreSQL 数据卷挂载 |  |  |  |
| Redis 数据卷或配置明确 |  |  |  |
| MinIO 数据卷挂载 |  |  |  |
| Elasticsearch 数据卷挂载 |  |  |  |
| Langfuse 数据持久化配置 |  |  |  |
| MinIO bucket 初始化脚本可执行 |  |  |  |
| ES index 初始化脚本可执行 |  |  |  |
| 数据库 migration 可自动或手动执行 |  |  |  |
| 服务启动顺序合理 |  |  |  |
| 服务端口规划与文档一致 |  |  |  |
| 所有服务有 health check |  |  |  |
| 日志目录可挂载 |  |  |  |
| `.env.example` 覆盖全部必需环境变量 |  |  |  |
| 本地一键启动脚本可执行 |  |  |  |
| 本地重置脚本可执行 |  |  |  |

---

## 19. CI/CD 与工程质量验收

| 验收项 | 状态 | 证据 | 说明 |
|---|---|---|---|
| Git 分支规范文档存在 |  |  |  |
| Commit Message 规范文档存在 |  |  |  |
| PR 模板或 PR 规范存在 |  |  |  |
| Code Review Checklist 存在 |  |  |  |
| Java 静态检查或格式化方案存在 |  |  |  |
| Python ruff / mypy / pytest 配置存在 |  |  |  |
| Frontend ESLint / TypeScript check 配置存在 |  |  |  |
| 自动化测试流程有文档或 CI 配置 |  |  |  |
| 镜像构建流程有文档或脚本 |  |  |  |
| 发布流程有文档 |  |  |  |
| 回滚策略有文档 |  |  |  |

---

## 20. Codex Phase 输出物验收

| Phase | 阶段名称 | 必须输出物 | 状态 | 证据 | 说明 |
|---|---|---|---|---|---|
| Phase 1 | 工程骨架初始化 | monorepo、基础目录、Docker Compose 初版、README |  |  |  |
| Phase 2 | Java Spring Boot 基础工程 | Spring Boot 工程、common/config、统一响应异常、health |  |  |  |
| Phase 3 | 数据库模型与 Migration | Flyway、核心表、Entity、Repository、枚举 |  |  |  |
| Phase 4 | Case Intake 与 Case 管理 | case API、Intake、Agent Client、缺槽处理 |  |  |  |
| Phase 5 | Evidence Dossier 与文件证据 | 上传、MinIO、Dossier、时间线、OCR 触发、ES 写入 |  |  |  |
| Phase 6 | OCR Parser Service | FastAPI、PaddleOCR、MarkItDown、MinIO/ES 对接 |  |  |  |
| Phase 7 | Router / Regular / Rule Flow | 路由、普通流、明确规则流、政策查询 |  |  |  |
| Phase 8 | Python Agent Service 与 C1-C6 | LangGraph、C1-C6、Prompt、schema、Langfuse |  |  |  |
| Phase 9 | Temporal Workflow 与 Hearing Controller | Workflow、Activities、Signals、超时、Hearing State |  |  |  |
| Phase 10 | Remedy Planner | 三路径到 Remedy Plan 的映射 |  |  |  |
| Phase 11 | Approval Policy Engine 与 Review | 审批规则、Review Task、Review Packet、审核台 |  |  |  |
| Phase 12 | Tool Executor | 审批校验、幂等、退款/补发/关闭/通知模拟 |  |  |  |
| Phase 13 | Case Closure 与 Evaluation Agent | Closure、Evaluation Trace、Evaluation Report |  |  |  |
| Phase 14 | Frontend 全流程页面 | 用户/商家补证、审核台、详情、证据、执行记录 |  |  |  |
| Phase 15 | Docker Compose 联调与部署验证 | Dockerfile、Nginx、初始化脚本、smoke test |  |  |  |
| Phase 16 | 全项目 Review 与正式验收 | 全量测试、E2E、规范检查、最终验收报告 |  |  |  |

---

## 21. 最终验收报告模板

Codex 完成验收后，必须输出以下格式的验收报告。

```markdown
# 订单履约争议裁决系统：正式版验收报告

## 1. 验收结论

- 总体结论：PASS / FAIL / PARTIAL / BLOCKED
- 是否允许进入正式交付：是 / 否
- 是否存在一票否决项：是 / 否
- 一票否决项编号：无 / VETO-xx

## 2. 验收范围

- 验收文档：订单履约争议裁决系统_正式版开发文档_Codex主控.md
- 验收清单：订单履约争议裁决系统_正式版一次性验收清单_Codex.md
- 验收仓库路径：xxx
- 验收时间：xxx
- 验收执行者：Codex

## 3. 总体统计

| 类别 | 总项数 | PASS | PARTIAL | FAIL | BLOCKED | N/A |
|---|---:|---:|---:|---:|---:|---:|
| 一票否决项 |  |  |  |  |  |  |
| 章节覆盖 |  |  |  |  |  |  |
| 服务与技术栈 |  |  |  |  |  |  |
| 仓库结构 |  |  |  |  |  |  |
| Java 后端 |  |  |  |  |  |  |
| Python Agent |  |  |  |  |  |  |
| Temporal Workflow |  |  |  |  |  |  |
| Tool Executor |  |  |  |  |  |  |
| API |  |  |  |  |  |  |
| 数据库与存储 |  |  |  |  |  |  |
| E2E 流程 |  |  |  |  |  |  |
| 前端 |  |  |  |  |  |  |
| 配置 |  |  |  |  |  |  |
| 异常错误码 |  |  |  |  |  |  |
| 日志 Trace 审计 |  |  |  |  |  |  |
| 安全 |  |  |  |  |  |  |
| 测试 |  |  |  |  |  |  |
| 部署 |  |  |  |  |  |  |
| CI/CD |  |  |  |  |  |  |
| Codex Phase |  |  |  |  |  |  |

## 4. 一票否决项检查结果

| 编号 | 结果 | 证据 | 说明 |
|---|---|---|---|
| VETO-01 |  |  |  |
| VETO-02 |  |  |  |
| VETO-03 |  |  |  |
| VETO-04 |  |  |  |
| VETO-05 |  |  |  |
| VETO-06 |  |  |  |
| VETO-07 |  |  |  |
| VETO-08 |  |  |  |
| VETO-09 |  |  |  |
| VETO-10 |  |  |  |
| VETO-11 |  |  |  |
| VETO-12 |  |  |  |
| VETO-13 |  |  |  |
| VETO-14 |  |  |  |

## 5. 失败项清单

| 编号 | 类别 | 验收项 | 问题描述 | 严重级别 | 修复建议 |
|---|---|---|---|---|---|
|  |  |  |  | BLOCKER / HIGH / MEDIUM / LOW |  |

## 6. 部分通过项清单

| 编号 | 类别 | 验收项 | 当前实现 | 缺口 | 修复建议 |
|---|---|---|---|---|---|
|  |  |  |  |  |  |

## 7. 阻塞项清单

| 编号 | 类别 | 阻塞原因 | 所需外部条件 | 临时处理建议 |
|---|---|---|---|---|
|  |  |  |  |  |

## 8. 测试执行结果

| 测试命令 | 结果 | 输出摘要 | 失败原因 |
|---|---|---|---|
| mvn test |  |  |  |
| pytest python-agent-service |  |  |  |
| pytest ocr-parser-service |  |  |  |
| npm run test |  |  |  |
| docker compose config |  |  |  |
| docker compose up -d |  |  |  |
| ./scripts/smoke-test.sh |  |  |  |

## 9. E2E 流程验收结果

| 流程 | 结果 | 证据 | 说明 |
|---|---|---|---|
| 普通履约流 |  |  |  |
| 明确规则流 |  |  |  |
| 争议审理流 |  |  |  |
| 审核员要求补证回流 |  |  |  |
| Tool Executor 幂等执行 |  |  |  |
| Case Closure + Evaluation |  |  |  |

## 10. 最终建议

- 是否可以交付：是 / 否
- 必须修复项：
  - xxx
- 建议优化项：
  - xxx
- 后续演进建议：
  - xxx
```

---

## 22. Codex 一次性验收提示词

将以下提示词直接复制给 Codex，可让 Codex 按本文档执行一次性总体验收。

```text
你现在作为 Codex，对“争议裁决驱动的人审门控订单履约协作系统”进行正式版一次性总体验收。

验收依据：
1. 订单履约争议裁决系统_正式版开发文档_Codex主控.md
2. 订单履约争议裁决系统_正式版一次性验收清单_Codex.md

任务目标：
- 读取主控开发文档和一次性验收清单。
- 扫描当前仓库所有代码、配置、文档、测试、Docker Compose、数据库 migration、Prompt、API、前端页面。
- 按验收清单逐项验收整个项目。
- 每个验收项必须给出 PASS / FAIL / PARTIAL / BLOCKED / N/A。
- 每个验收项必须给出证据位置。
- 对所有 FAIL / PARTIAL / BLOCKED 项给出修复建议。
- 最终输出正式版验收报告。

最高优先级约束：
- 不允许把正式版降级为 MVP。
- 不允许只验收核心链路。
- 不允许遗漏 A/B/Router/C/D/Approval/Human Review/Tool Executor/Case Closure/Evaluation 任一模块。
- 不允许 Agent 直接输出最终裁决。
- 不允许 C 层直接执行退款、补发、关闭售后。
- 不允许 D 层重新断案。
- 不允许 Tool Executor 执行未审批动作。
- 不允许绕过 Platform Human Review。
- 不允许引入 MCP、Kafka、Milvus、Qdrant、Kubernetes、K3s、Helm、OPA、Drools、vLLM、A2A、CrewAI、完整微服务拆分。
- 不允许设计申诉 / 复审流程。
- 不允许跳过测试后声明验收通过。

必须检查：
1. 一票否决项。
2. 主控文档 21 个章节覆盖情况。
3. Docker Compose 服务完整性。
4. monorepo 仓库结构。
5. Java 后端分层和模块完整性。
6. Python Agent Service 与 C1-C6。
7. Temporal Workflow 与 Hearing Controller。
8. Tool API 与 Tool Executor 安全边界。
9. REST API、统一响应、错误码、幂等、OpenAPI。
10. PostgreSQL 表、Elasticsearch 索引、Redis Key、MinIO Bucket。
11. 普通履约流、明确规则流、争议审理流 E2E。
12. 前端补证页、审核台、证据展示、执行记录。
13. 配置、环境变量、Feature Flag、敏感配置。
14. 异常处理、日志、Trace、审计、Langfuse。
15. 安全、权限、Prompt 注入防护、Tool 权限矩阵。
16. 单元测试、集成测试、API 测试、Workflow 测试、Agent 测试、Tool 测试、权限测试、回归测试、性能测试、部署验证。
17. CI/CD、代码规范、Review 规范、发布和回滚文档。
18. Phase 1-16 输出物是否完整。

必须尝试执行或检查以下命令：
- cd java-api-service && mvn test
- cd python-agent-service && pytest
- cd ocr-parser-service && pytest
- cd frontend && npm install && npm run test
- docker compose config
- docker compose up -d
- ./scripts/smoke-test.sh

输出要求：
- 严格使用验收报告模板输出。
- 不要只给总结。
- 不要跳过失败项。
- 不要因为某项难以验证就默认 PASS。
- 对无法验证项标记 BLOCKED，并说明阻塞原因。
- 对当前版本明确不做的事项标记 N/A，并引用主控文档依据。

最终结论规则：
- 任何一票否决项 FAIL，则总体结论必须为 FAIL。
- 核心链路任一 E2E 不通过，则总体结论不得为 PASS。
- 测试命令无法运行或失败且无合理说明，则总体结论不得为 PASS。
- Docker Compose 无法启动核心服务，则总体结论不得为 PASS。
- 缺少人审门控或 Tool Executor 审批校验，则总体结论必须为 FAIL。
```

---

## 23. 验收通过判定规则

### 23.1 PASS 条件

只有同时满足以下条件，项目才能判定为正式版验收通过：

- 所有一票否决项均非 FAIL。
- 主控文档 21 个章节均已覆盖。
- A/B/Router/C/D/Approval/Human Review/Tool Executor/Case Closure/Evaluation 全部实现。
- Docker Compose 全服务可启动。
- 普通履约、明确规则、争议审理三条主流程 E2E 均通过。
- 人审门控不可绕过。
- Tool Executor 未审批动作不可执行。
- Agent 不直接裁决、不直接执行。
- 核心数据库表完整。
- 核心 API 可用。
- 核心测试通过。
- 日志、Trace、审计、安全边界可验证。
- 未引入当前版本禁止技术。

### 23.2 FAIL 条件

出现以下任一情况，总体结论必须为 FAIL：

- 存在任意一票否决项 FAIL。
- 任一核心模块缺失。
- 任一主流程完全不可运行。
- 未实现 Platform Human Review。
- 未实现 Tool Executor 审批校验。
- Agent 可直接执行高风险动作。
- C 层可直接退款、补发、关闭售后。
- Docker Compose 无法启动核心服务。
- 核心数据库 migration 不可执行。
- 大量测试失败或被删除。
- 引入主控文档明确禁止的技术。

### 23.3 PARTIAL 条件

以下情况总体可判为 PARTIAL，但不得判为 PASS：

- 核心流程可运行，但部分管理页面缺少细节。
- 核心 API 可用，但部分错误码或 OpenAPI 不完整。
- Agent 主流程可用，但 Evaluation Agent 评估指标不完整。
- Docker 可启动，但部分监控或告警仅有文档未实现。
- 测试覆盖不足但核心 E2E 可运行。

### 23.4 BLOCKED 条件（标记为blocked可以先跳过）

以下情况可以标记 BLOCKED：

- 缺少外部支付 / 退款 / 物流真实接口，只能验证模拟 Tool API。
- 当前环境无法启动 Docker。
- 当前环境无法下载依赖。
- 当前环境资源不足导致 Elasticsearch / Langfuse / Temporal 无法启动。

BLOCKED 必须说明：

- 阻塞原因。
- 已完成的静态检查证据。
- 解除阻塞需要什么条件。
- 阻塞解除后应执行哪些验证。
