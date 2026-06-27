# 订单履约争议裁决系统：正式版开发文档（Codex 主控版）

> 项目名称：争议裁决驱动的人审门控订单履约协作系统  
> 英文名称：Dispute-Centric Human-Gated Agentic Fulfillment System  
> 文档用途：正式版工程开发主控文档 / Codex 开发指挥文档  
> 核心原则：不做 MVP、不做简化版、不省略架构模块、不绕过 Human-in-the-loop

---

## 1. 文档说明

本文档用于指导 Codex 从零到一完成“争议裁决驱动的人审门控订单履约协作系统”的正式版开发，并作为后续开发、测试、部署、维护、Code Review 和架构验收的主控文档。

本文档用于：

- 指导 Codex 进行正式版项目开发。
- 约束项目工程结构、代码分层、接口规范、数据库规范、测试规范和部署规范。
- 保证上传架构能够完整落地。
- 明确 Java、Python、前端、Workflow、Agent、Tool、数据库、缓存、对象存储、检索、OCR、LLM Trace、部署等模块边界。
- 作为最终验收、代码审查、工程质量评估和后续迭代依据。

本文档严格基于：

- `order_fulfillment_dispute_agentic_architecture.md`
- `订单履约争议裁决系统_最终技术清单.md`

### 1.1 最高优先级约束

1. 不做 MVP，不做 Demo，不只跑核心链路。
2. 不省略架构中已经规划的模块。
3. 不引入技术清单明确暂不采用的技术。
4. 不允许 Agent 直接作为最终法官。
5. 不允许 C 层直接执行退款、补发、关闭售后。
6. 不允许 D 层重新断案。
7. 不允许 Tool Executor 执行未审批动作。
8. 所有影响用户权益和商家成本的动作必须经过平台审核员确认。
9. 不设计申诉 / 复审流程。

### 1.2 待确认事项

> 待确认事项：
>
> - 技术清单标题中出现“Docker 本地 MVP 版”，但本次开发要求是正式版。本文档按“正式版功能完整覆盖 + Docker Compose 本地正式部署”处理。
> - 真实订单、支付、物流、仓储、退款、补发、消息系统接口未提供，当前版本用 Spring Boot Tool API 模拟封装，并预留 Adapter。
> - 真实认证体系未提供，当前版本设计本地角色权限模型。
> - 真实平台政策、商家政策、类目规则、举证责任规则内容未提供，当前版本实现政策管理、政策版本、检索和规则适用能力。
> - 当前版本不做申诉 / 复审，case 在平台审核确认并执行后闭环。

---

## 2. 项目背景与建设目标

### 2.1 项目背景

系统面向订单履约争议场景，重点不是普通订单查询，而是售后争议、履约纠纷、物流签收争议、退货退款争议、商品损坏、掉包、少件、错发、证据不完整、责任边界模糊和平台裁决流程。

系统采用 Human-Gated Agentic Workflow：

- Workflow：流程边界、状态流转、暂停恢复、审批控制、审计记录。
- Agent：自然语言理解、争点归纳、证据缺口识别、补证请求生成、证据矛盾分析、规则适用说明、裁决草案生成。
- Human-in-the-loop：高风险节点确认，尤其是平台审核员确认裁决草案和执行方案。
- Tool Executor：退款、补发、驳回、关闭售后、创建工单、通知双方等确定性执行。
- Evaluation Agent：离线复盘和质量评估，不参与当前 case 实时裁决。

### 2.2 项目要解决的问题

系统需要支持：

1. 自动识别纠纷类型。
2. 自动收集平台客观证据。
3. 组织用户和商家补证。
4. 归纳争点。
5. 分析证据支持、冲突、缺失关系。
6. 适用平台、商家、类目、举证责任规则。
7. 生成可解释、可审核、可修改、可追溯的裁决草案。
8. 将裁决草案映射为 Remedy Plan。
9. 经过审批策略和平台审核员确认。
10. 由 Tool Executor 执行确定性动作。
11. 记录审计日志、Workflow 状态、Agent Trace。
12. 支撑离线 Evaluation Agent 评估裁决质量、规则缺口和流程缺陷。

### 2.3 核心业务价值

- 降低复杂售后争议人力成本。
- 提升证据整理效率。
- 提升审核员决策效率。
- 通过人审门控控制高风险动作。
- 提升裁决草案可解释性和可追溯性。
- 沉淀案例、证据缺口、规则缺陷和 Agent 评估数据。

### 2.4 正式版建设目标

正式版必须完整实现：

- 普通履约流。
- 明确规则流。
- 争议审理流。
- D 层 Remedy Planner。
- Approval Policy Engine。
- Platform Human Review。
- Tool Executor。
- Case Closure。
- Evaluation Agent。
- 全部 Agent 节点。
- 核心数据对象。
- 分层架构、REST API、数据库、Temporal Workflow、LangGraph Agent Workflow、Tool API、OCR、Elasticsearch、MinIO、Redis、Langfuse、Docker Compose、日志、Trace、审计、安全、测试、CI/CD。

---

## 3. 总体架构理解

### 3.1 总体链路

```text
用户 / 商家 / 平台客服 / 平台审核员
        ↓
Vue3 前端
        ↓
Nginx
        ↓
Spring Boot API
        ↓
Temporal Java Workflow
        ↓
Python FastAPI + LangGraph
        ↓
LiteLLM Proxy
        ↓
LLM Provider
```

业务数据链路：

```text
Spring Boot API
        ↓
PostgreSQL / Redis / MinIO / Elasticsearch
```

Agent Trace：

```text
Python Agent Service
        ↓
Langfuse
```

证据解析：

```text
MinIO 文件
        ↓
OCR Parser Service
        ↓
PostgreSQL + Elasticsearch
```

### 3.2 服务拆分

正式版 Docker Compose 包含：

```text
frontend
java-api-service
temporal-server
python-agent-service
litellm-proxy
langfuse
postgresql
redis
elasticsearch
minio
ocr-parser-service
nginx
```

### 3.3 服务职责

| 服务 | 技术栈 | 职责 |
|---|---|---|
| frontend | Vue3 + Vite + Element Plus | 用户补证页、商家补证页、审核台、Case 时间线、证据卷宗、裁决草案、执行方案确认 |
| java-api-service | Java 21 + Spring Boot 3 | case、证据、审批、审核、执行、审计、Tool API、Temporal Worker |
| temporal-server | Temporal + Java SDK | Case 生命周期、补证等待、人审等待、超时恢复、任务重试 |
| python-agent-service | Python FastAPI + LangGraph | C 层争议审理 Agent Workflow |
| litellm-proxy | LiteLLM Proxy | 统一模型调用、模型切换、fallback、成本统计 |
| langfuse | Langfuse | Prompt、模型调用、Agent 输出、工具调用、裁决草案 Trace |
| postgresql | PostgreSQL | 业务数据、证据元数据、审计、审核、执行记录及周边系统数据 |
| redis | Redis | 会话缓存、任务状态缓存、限流、任务锁、短期状态 |
| elasticsearch | Elasticsearch | 政策、证据文本、历史 case、聊天记录、质检记录检索 |
| minio | MinIO | 图片、截图、PDF、质检文件、物流证明、视频截图、OCR 原始文件 |
| ocr-parser-service | PaddleOCR + MarkItDown | OCR、文件转文本、写 Evidence Store 和 Elasticsearch |
| nginx | Nginx | 统一代理前端、Java API、Python Agent API、Langfuse、LiteLLM |

### 3.4 主调用关系

```text
Frontend
  -> Java API Service
    -> Temporal Workflow
      -> Java Activities
        -> Case Service
        -> Evidence Service
        -> Dispute Router
        -> Regular Flow / Rule Flow / Python Agent Service
        -> Remedy Planner
        -> Approval Policy Engine
        -> Platform Human Review
        -> Tool Executor
        -> Case Closure
```

### 3.5 Java 与 Python 通信

当前版本采用 REST API。

原则：

- Java 是业务主控。
- Python Agent Service 不直接修改核心业务状态。
- Python 输出结构化结果，由 Java 校验、落库、流转。
- Python 不直接调用退款、补发、关闭售后。
- 跨服务请求必须携带 `request_id`、`case_id`、`trace_id`。
- Java 调 Python 必须设置超时、重试和降级。
- Python 返回结果必须通过 schema 校验。

### 3.6 三条业务路径

普通履约流：

```text
A -> B -> Router -> Regular Fulfillment Flow -> D -> Approval -> Human Review -> Tool Executor -> Case Closure
```

明确规则流：

```text
A -> B -> Router -> Rule-based Resolution Flow -> D -> Approval -> Human Review -> Tool Executor -> Case Closure
```

争议审理流：

```text
A -> B -> Router -> C Dispute Hearing Workflow -> D -> Approval -> Human Review -> Tool Executor -> Case Closure
```

### 3.7 核心数据流

```text
自然语言输入
  -> Intake Result
  -> Fulfillment Case
  -> Evidence Dossier
  -> Claim-Issue-Evidence Matrix
  -> Hearing State
  -> Issue List
  -> Evidence Request List
  -> Party Submission
  -> Evidence Cross-check Result
  -> Policy Application Result
  -> Adjudication Draft
  -> Remedy Plan
  -> Review Packet
  -> Approval Record
  -> Action Record
  -> Evaluation Trace
```

---

## 4. 正式版功能范围

### 4.1 Case Intake Agent

#### 模块职责

承接用户、商家、客服输入，将自然语言输入转换为结构化 case，完成问题类型、售后类型、纠纷类型和初始风险等级识别。

#### 核心能力

- 识别普通履约问题、售后申请、争议纠纷。
- 抽取订单号、售后单号、物流单号。
- 识别用户诉求、商家主张。
- 判断双方说法冲突。
- 识别缺失槽位。
- 创建 Fulfillment Case。
- 触发 Evidence Dossier Builder。

#### 输入

- 自然语言输入。
- 附件列表。
- 订单号 / 售后单号 / 物流单号。
- 当前登录角色。
- 渠道来源。

#### 输出

- `FulfillmentCase`
- `CaseIntakeResult`
- `MissingSlotList`
- `InitialRiskLevel`
- `PotentialDisputeFlag`

#### 依赖模块

订单查询 Tool、售后查询 Tool、历史 case 查询、Python Agent Service、PostgreSQL、Redis、Langfuse。

#### 关键实现点

- 不直接裁决。
- 不承诺退款、补发或驳回。
- 缺少关键槽位时进入 `WAITING_SLOT_COMPLETION`。
- 多渠道入口统一归一为 `CreateCaseCommand`。

#### 异常情况

订单不存在、售后单不存在、输入不足、无权访问、Agent 超时、LLM 返回非结构化、重复提交。

#### 验收标准

可创建普通履约、明确规则、争议 case；可识别缺槽；不生成最终裁决；创建 case 后有审计日志；Agent 调用写 Langfuse Trace。

### 4.2 Evidence Dossier Builder

#### 模块职责

构建平台证据卷宗，收集订单、支付、物流、售后、聊天、仓储、质检、用户材料、商家材料等证据，并构建时间线和主张—争点—证据矩阵。

#### 核心能力

- 收集订单、支付、物流、售后、聊天、仓储、质检证据。
- 收集用户和商家提交材料。
- 构建 Case Timeline。
- 整理 Party Claims。
- 生成 Evidence List、Missing Evidence、Policy Candidates。
- 生成 Claim-Issue-Evidence Matrix。
- 文件入 MinIO，文本入 Elasticsearch。

#### 输入

`case_id`、`order_id`、`after_sale_id`、`user_id`、`merchant_id`、附件、工具查询结果。

#### 输出

`EvidenceDossier`、`CaseTimeline`、`PartyClaims`、`EvidenceList`、`MissingEvidenceList`、`PolicyCandidates`、`ClaimIssueEvidenceMatrix`。

#### 依赖模块

Order / Payment / Logistics / After-sale / Warehouse / Chat / Evidence Tool、OCR Parser、PostgreSQL、MinIO、Elasticsearch。

#### 关键实现点

- B 层只做证据收集与结构化，不判断责任。
- 证据必须记录来源、时间、文件哈希、提交方、可见范围、脱敏状态。
- Evidence Dossier 更新必须产生版本号。
- 原始材料与脱敏材料分开。

#### 异常情况

外部工具失败、MinIO 失败、OCR 失败、文件类型不支持、文件超限、重复证据、ES 写入失败、越权访问。

#### 验收标准

可上传多格式证据；可保存到 MinIO；可解析写入 ES；可生成时间线和矩阵；不输出裁决结论。

### 4.3 Dispute Router

#### 模块职责

根据 case 类型、证据充分性、双方说法冲突、关键证据缺口、风险等级和规则明确程度选择后续路径。

#### 核心能力

- 判断普通履约流。
- 判断明确规则流。
- 判断争议审理流。
- 标记高风险和需要补证。
- 保证所有路径最终进入 D、审批、人审、执行层。

#### 输入

`FulfillmentCase`、`EvidenceDossier`、`ClaimIssueEvidenceMatrix`、`InitialRiskLevel`、`PolicyCandidates`。

#### 输出

`RouteDecision`，包括 `REGULAR_FULFILLMENT`、`RULE_BASED_RESOLUTION`、`DISPUTE_HEARING`。

#### 依赖模块

Case Service、Evidence Service、Rule Service、Risk Config、PostgreSQL。

#### 关键实现点

Router 不裁决、不结束 case、不触发执行动作。路由决策必须持久化并记录理由。

#### 验收标准

三类路径可正确路由；所有路由结果可继续进入 D 层；路由可审计。

### 4.4 Regular Fulfillment Flow

#### 模块职责

处理查物流、催发货、查询预计送达、普通状态解释等无明显纠纷的问题。

#### 核心能力

查询订单状态、物流轨迹、解释发货状态、生成通知或催办方案、输出普通处理结论。

#### 关键实现点

不进入 C 层，但必须进入 D 层；通知、催办、工单动作仍需审批和人审链路。

#### 验收标准

查物流和催发货可形成处理结论；处理结论进入 D；不允许绕过人审直接通知。

### 4.5 Rule-based Resolution Flow

#### 模块职责

处理证据充分、规则明确、无需复杂质证的 case。

#### 核心能力

匹配规则、判断证据充分性、生成规则处理结论、标注规则依据和风险等级。

#### 关键实现点

不进入 C，但必须进入 D、审批、人审、执行；必须引用政策版本；不使用常识替代平台规则。

#### 验收标准

未发货取消、商家同意退款可生成规则结论；高风险 case 不得误入低风险规则处理。

### 4.6 Dispute Hearing Workflow

#### 模块职责

处理证据冲突、责任不清、双方说法矛盾的争议审理，是系统核心。

#### 核心能力

创建 Hearing State、控制 C1-C6、控制补证轮次和超时、等待用户 / 商家 / 审核员、归纳争点、识别缺证、生成补证请求、交叉检查证据、适用规则、生成裁决草案和审核包。

#### 关键实现点

- C0 Hearing Controller 由 Temporal + Java 控制。
- C1-C6 由 Python LangGraph 实现。
- 补证等待、人审等待、超时恢复由 Temporal 控制。
- Agent 输出必须结构化并通过 Pydantic 校验。
- C 层只能生成裁决建议和审核包，不执行退款、补发、关闭售后。

#### 验收标准

C1-C6 可完整执行；可补证等待和恢复；可处理超时；可生成结构化草案和审核包；Agent Trace 写入 Langfuse。

### 4.7 Remedy Planner

#### 模块职责

将普通履约结论、明确规则结论或 C 层裁决草案映射为可审核、可执行的 Remedy Plan。

#### 核心能力

读取上游结论、识别动作、生成执行方案、通知方案、审核摘要、风险等级和审批需求。

#### 关键实现点

不重新断案、不推翻 C 的事实认定、不直接执行动作。每个 action 必须声明动作类型、参数、幂等键、前置条件、风险等级。

#### 验收标准

三条路径均能生成 Remedy Plan；高风险动作标记审批。

### 4.8 Approval Policy Engine

#### 模块职责

根据执行方案、金额、风险、角色权限和平台规则判断审批路径。

#### 核心能力

判断平台审核、商家确认、用户确认、风控审核、客服接管；生成审批路径和审核材料；标注高风险动作。

#### 关键实现点

使用 Spring Boot 内置规则服务 + 配置表，不引入 Drools / OPA。退款、补发、驳回、关闭售后默认进入平台审核员确认。

#### 验收标准

退款超阈值、高价值补发、掉包争议、证据不足大额订单进入人审；未审批动作不能执行。

### 4.9 Platform Human Review

#### 模块职责

平台审核员最终确认节点，是系统责任锚点。

#### 核心能力

展示 Review Packet、证据卷宗、争点矩阵、规则适用、裁决草案、执行方案、风险提示、置信度；支持确认、修改、驳回、要求补证、转人工、批准执行。

#### 关键实现点

审核员修改必须保留 diff；动作必须审计；要求补证时 Temporal 回到补证等待；批准后才进入 Tool Executor。

#### 验收标准

审核员可查看完整审核包并执行全部审核动作；批准后才能执行；要求补证可恢复流程。

### 4.10 Tool Executor

#### 模块职责

执行已经通过审批的退款、补发、换货、驳回售后、关闭售后、创建工单、通知等确定性动作。

#### 核心能力

审批校验、幂等校验、动作执行模拟、Action Record 记录、case 状态更新、失败重试和补偿记录。

#### 关键实现点

只执行审批通过动作；每个动作必须有幂等键；执行失败不能修改裁决草案和审核意见。

#### 验收标准

未审批动作被拒绝；审批通过动作可执行；重复请求被幂等拦截；失败有记录。

### 4.11 Case Closure

#### 模块职责

执行完成后关闭 case，记录结果、沉淀审计日志，并生成 Evaluation Trace。

#### 关键实现点

关闭前确认审批和执行状态；关闭后不允许修改核心裁决结果；Evaluation Trace 不参与当前 case 实时裁决。

#### 验收标准

case 可关闭；关闭记录可查询；Evaluation Trace 可生成。

### 4.12 Evaluation Agent

#### 模块职责

离线复盘裁决草案质量、审核修改率、证据缺口、规则缺口、流程缺陷和 Agent 策略缺陷。

#### 关键实现点

只分析 closed case；不参与实时裁决；不自动修改规则或 prompt。

#### 验收标准

可生成离线评估报告；统计草案通过率和审核修改率；输出规则缺口建议。

### 4.13 OCR Parser Service

#### 模块职责

解析图片、PDF、Word、Excel 等证据材料，将非结构化文件转换为 LLM 和检索可用文本。

#### 关键实现点

读取 MinIO 文件；PaddleOCR 识别中文图片；MarkItDown 转文档文本；解析结果写 PostgreSQL 和 ES；原始文件不覆盖。

#### 验收标准

图片、PDF、Word、Excel 可解析；结果可查询和检索。

### 4.14 Frontend 审核与补证模块

#### 模块职责

用户补证页、商家补证页、平台审核台、case 详情、证据卷宗、时间线、裁决草案、执行方案、审计记录展示。

#### 关键实现点

前端不写裁决逻辑；所有状态以后端为准；审核操作二次确认；高风险动作明显提示。

#### 验收标准

用户和商家可补证；审核员可审核；无越权访问。

---

## 5. 仓库结构设计

### 5.1 仓库组织方式

推荐使用 monorepo。

原因：

- 技术清单为 Docker Compose 多服务部署，monorepo 更适合统一管理。
- Java、Python、前端、部署、文档、测试可统一版本控制。
- 适合 Codex 分阶段开发和本地端到端联调。
- 当前版本不做完整微服务拆分，multi-repo 会增加复杂度。

### 5.2 推荐仓库结构

```text
order-fulfillment-dispute-system/
  README.md
  CONTRIBUTING.md
  CODE_STYLE.md
  SECURITY.md
  .gitignore
  .editorconfig
  .env.example
  docker-compose.yml

  docs/
    architecture/
    api/
    database/
    deployment/
    codex/

  deploy/
    nginx/
    postgresql/
    elasticsearch/
    minio/
    litellm/
    langfuse/
    temporal/

  scripts/
    dev-up.sh
    dev-down.sh
    dev-reset.sh
    init-db.sh
    init-minio.sh
    init-es.sh
    smoke-test.sh
    generate-openapi.sh

  frontend/
    package.json
    vite.config.ts
    tsconfig.json
    src/
      main.ts
      App.vue
      router/
      api/
      stores/
      views/
      components/
      utils/
      tests/

  java-api-service/
    pom.xml
    Dockerfile
    src/
      main/
        java/com/example/dispute/
          DisputeApplication.java
          common/
          config/
          modules/
            casecore/
            intake/
            evidence/
            router/
            regularflow/
            ruleflow/
            hearing/
            remedy/
            approval/
            review/
            executor/
            evaluation/
            policy/
            tool/
            audit/
            workflow/
          integration/
        resources/
          application.yml
          application-local.yml
          db/migration/
      test/

  python-agent-service/
    pyproject.toml
    Dockerfile
    app/
      main.py
      api/
      core/
      clients/
      prompts/
      graphs/
      agents/
      services/
      models/
      tests/

  ocr-parser-service/
    pyproject.toml
    Dockerfile
    app/
      main.py
      api/
      core/
      clients/
      parsers/
      services/
      tests/

  tests/
    api/
    e2e/
    load/
    fixtures/

  infra-tests/
    docker-compose-healthcheck.md
    smoke-test-cases.md
```

### 5.3 目录职责

| 目录 | 职责 |
|---|---|
| `docs/` | 架构、API、数据库、部署、Codex 开发文档 |
| `deploy/` | Nginx、PostgreSQL、ES、MinIO、LiteLLM、Temporal 配置 |
| `scripts/` | 启停、初始化、重置、冒烟测试 |
| `frontend/` | Vue3 前端 |
| `java-api-service/` | Spring Boot 主业务后端、Tool API、Temporal Worker |
| `python-agent-service/` | FastAPI + LangGraph Agent 服务 |
| `ocr-parser-service/` | OCR 与文件转文本 |
| `tests/` | API、E2E、性能、fixtures |
| `infra-tests/` | 基础设施健康检查 |

---

## 6. 后端工程结构规范

### 6.1 Java 后端分层

每个业务模块采用：

```text
modules/{module-name}/
  api/
  application/
  domain/
  infrastructure/
```

| 层 | 职责 | 禁止事项 |
|---|---|---|
| api/controller | 接收 HTTP、参数校验、调用 application、返回统一响应 | 不写业务逻辑、不访问 DB、不调用外部 API |
| application/usecase | 编排业务用例、事务边界、状态流转、调用 domain/repository/client | 不暴露 Entity、不拼 Prompt |
| domain | 领域模型、领域规则、状态机、枚举、值对象 | 不依赖 Web/DB/外部 HTTP |
| infrastructure | Repository、Mapper、Entity 转换、MinIO/Redis/ES/Agent/OCR Client | 不承载核心业务规则 |
| repository/mapper | 数据访问 | 不写业务判断、不调用外部服务 |
| config | 配置类 | 不写业务逻辑 |
| common | 统一响应、异常、日志、鉴权、幂等、校验 | 不放模块私有逻辑 |
| integration/client | 外部服务调用 | 必须有超时、重试、错误码映射和 Trace 透传 |

### 6.2 DTO / VO / Entity / Domain 边界

| 类型 | 用途 | 禁止事项 |
|---|---|---|
| Request DTO | API 入参 | 不作为数据库实体 |
| Response VO | API 出参 | 不包含内部敏感字段 |
| Entity / DO | 数据库表映射 | 不直接返回前端 |
| Domain Model | 领域逻辑对象 | 不携带 HTTP 注解 |
| Command | 应用层业务命令 | 不直接落库 |
| Client DTO | 外部服务对象 | 不进入领域层 |
| Event / Signal | Temporal 事件或信号 | 不作为 API 响应 |

### 6.3 事务边界

- 创建 case、创建 dossier、创建 workflow 记录可在一个应用事务中完成。
- 调用 Python Agent Service 不包含在数据库长事务中。
- 调用外部系统不持有数据库长事务。
- Tool Executor 一个动作一个事务记录执行状态。
- 多动作执行通过 ActionRecord 和补偿状态保证一致性。
- Temporal Activity 必须可重试。

---

## 7. Agent / Workflow / Tool 工程设计

### 7.1 Agent 列表

| Agent | 位置 | 职责 |
|---|---|---|
| Case Intake Agent | A | 入口意图识别、纠纷识别、诉求抽取 |
| Issue Framing Agent | C1 | 争点归纳 |
| Evidence Gap & Request Agent | C2 | 证据缺口识别与补证请求生成 |
| Party Liaison Agent | C3 | 中立联络双方并结构化提交材料 |
| Evidence Cross-check Agent | C4 | 证据支持、冲突、缺失检测 |
| Rule Application Agent | C5 | 规则检索与适用说明 |
| Adjudication Draft Agent | C6 | 裁决草案和审核重点生成 |
| Evaluation Agent | 离线 | closed case 质量评估 |

### 7.2 全局 Workflow

由 Temporal + Java SDK 实现，Workflow 名称建议：

```text
CaseFulfillmentDisputeWorkflow
```

核心状态：

```text
CASE_CREATED
INTAKE_COMPLETED
DOSSIER_BUILT
ROUTE_DECIDED
REGULAR_FLOW_PROCESSING
RULE_FLOW_PROCESSING
DISPUTE_HEARING_PROCESSING
REMEDY_PLANNED
APPROVAL_POLICY_APPLIED
WAITING_HUMAN_REVIEW
APPROVED_FOR_EXECUTION
EXECUTING_TOOLS
CASE_CLOSED
EVALUATION_READY
```

Temporal 负责长流程状态、补证等待、人审等待、超时、Activity 重试、Signal 恢复。

### 7.3 C 层 LangGraph

Python Agent Service 中实现：

```text
issue_framing_node
evidence_gap_request_node
party_liaison_node
evidence_cross_check_node
rule_application_node
adjudication_draft_node
```

C0 Hearing Controller 由 Java Temporal 控制，不是自由 Agent。

### 7.4 Tool 设计

当前不采用 MCP，Tool 由 Spring Boot Tool API 封装。

Tool 类型：

- 订单工具。
- 物流工具。
- 售后工具。
- 仓储工具。
- 证据工具。
- 政策工具。
- 消息工具。
- 审批工具。
- 执行工具。

执行类 Tool 必须声明：

```json
{
  "tool_name": "refund.create",
  "risk_level": "HIGH",
  "requires_approval": true,
  "idempotent": true,
  "idempotency_key_required": true
}
```

### 7.5 Router 策略

输出：

```text
REGULAR_FULFILLMENT
RULE_BASED_RESOLUTION
DISPUTE_HEARING
```

判断因素：

- 双方说法冲突。
- 关键证据缺口。
- 高风险争议类型。
- 是否存在明确平台规则。
- 商家是否明确同意。
- 是否需要补证。
- 是否涉及高价值商品或高风险主体。

### 7.6 Trace 规范

业务 Trace 存 PostgreSQL；Agent Trace 存 Langfuse；系统 Trace 在日志中记录。

必须记录：

- `trace_id`
- `request_id`
- `case_id`
- `workflow_id`
- `span_id`
- `user_id`
- `role`
- `node_name`
- `prompt_version`
- `model`
- `latency`
- `token_usage`

### 7.7 Prompt 管理

Prompt 存放在：

```text
python-agent-service/app/prompts/
```

要求：

- 每个 Agent 一个 prompt。
- prompt 声明角色、输入结构、输出 schema、禁止行为、风险边界。
- 不在业务代码硬编码长 prompt。
- 输出经过 Pydantic 校验。

### 7.8 失败重试与降级

| 场景 | 策略 |
|---|---|
| Python Agent 超时 | Temporal Activity 重试，仍失败标记 manual_required |
| LLM 调用失败 | LiteLLM fallback，仍失败进入人工重点审核 |
| Agent 输出 schema 不合法 | 重试一次结构化修复，仍失败进入人工重点审核 |
| OCR 失败 | 标记解析失败，允许人工查看原文件 |
| ES 写入失败 | 主流程不阻塞，记录告警 |
| Tool 执行失败 | ActionRecord failed，允许重试或人工处理 |
| 人审超时 | 标记 review_timeout，提醒或转人工 |
| 补证超时 | 进入 C5/C6 或人工重点审核 |

### 7.9 HITL 场景

必须进入人审：

- 退款。
- 补发。
- 换货。
- 驳回售后。
- 关闭售后。
- 高价值商品。
- 掉包争议。
- 签收争议。
- 证据不足但涉及大额订单。
- Agent 置信度低。
- 审批规则标记高风险。
- C 层输出 `manual_required`。

---

## 8. API 接口设计规范

### 8.1 REST 规范

- API 前缀：`/api/v1`
- 响应统一使用 `ApiResponse`
- 写操作支持 `Idempotency-Key`
- 所有接口生成 OpenAPI / Swagger
- 分页使用 `page`、`page_size`、`sort`
- 错误码统一

### 8.2 统一响应

成功：

```json
{
  "success": true,
  "code": "SUCCESS",
  "message": "success",
  "data": {},
  "request_id": "REQ_xxx",
  "trace_id": "TRACE_xxx",
  "timestamp": "2026-06-28T10:00:00Z"
}
```

失败：

```json
{
  "success": false,
  "code": "CASE_NOT_FOUND",
  "message": "case not found",
  "details": {},
  "request_id": "REQ_xxx",
  "trace_id": "TRACE_xxx",
  "timestamp": "2026-06-28T10:00:00Z"
}
```

### 8.3 核心 API 清单

| 接口 | Method | Path | 说明 | 幂等 |
|---|---|---|---|---|
| 创建 Case | POST | `/api/v1/cases` | 创建履约问题或争议 case | 是 |
| 查询 Case | GET | `/api/v1/cases/{case_id}` | 查询 case 详情 | 否 |
| 构建证据卷宗 | POST | `/api/v1/cases/{case_id}/dossier/build` | 构建或刷新 dossier | 是 |
| 查询证据卷宗 | GET | `/api/v1/cases/{case_id}/dossier` | 查询证据卷宗 | 否 |
| 上传证据 | POST | `/api/v1/cases/{case_id}/evidences` | 上传证据文件 | 是 |
| 路由 Case | POST | `/api/v1/cases/{case_id}/route` | 执行 Dispute Router | 是 |
| 启动 Workflow | POST | `/api/v1/cases/{case_id}/workflow/start` | 启动 Temporal Workflow | 是 |
| 查询 Hearing | GET | `/api/v1/cases/{case_id}/hearing` | 查询审理状态 | 否 |
| 用户补证 | POST | `/api/v1/cases/{case_id}/submissions/user` | 用户提交补证 | 是 |
| 商家补证 | POST | `/api/v1/cases/{case_id}/submissions/merchant` | 商家提交补证 | 是 |
| 查询草案 | GET | `/api/v1/cases/{case_id}/adjudication-draft` | 查询裁决草案 | 否 |
| 查询执行方案 | GET | `/api/v1/cases/{case_id}/remedy-plan` | 查询 Remedy Plan | 否 |
| 审核任务列表 | GET | `/api/v1/review-tasks` | 查询待审核任务 | 否 |
| 查询审核包 | GET | `/api/v1/review-tasks/{task_id}/packet` | 查询 Review Packet | 否 |
| 提交审核 | POST | `/api/v1/review-tasks/{task_id}/decision` | 确认、修改、驳回、补证、转人工 | 是 |
| 执行动作 | POST | `/api/v1/cases/{case_id}/execution/execute` | 执行审批通过动作 | 是 |
| 查询执行记录 | GET | `/api/v1/cases/{case_id}/actions` | 查询 ActionRecord | 否 |
| 关闭 Case | POST | `/api/v1/cases/{case_id}/close` | 关闭 case | 是 |
| 查询评估 | GET | `/api/v1/cases/{case_id}/evaluation` | 查询 Evaluation Report | 否 |

### 8.4 Python Agent API

| 接口 | Method | Path | 说明 |
|---|---|---|---|
| Intake 分析 | POST | `/agent-api/v1/intake/analyze` | 输出 Intake Result |
| Hearing 分析 | POST | `/agent-api/v1/hearings/analyze` | 执行 C 层 LangGraph |
| Evaluation 分析 | POST | `/agent-api/v1/evaluations/analyze` | closed case 离线评估 |

### 8.5 OCR API

| 接口 | Method | Path | 说明 |
|---|---|---|---|
| 创建解析任务 | POST | `/ocr-api/v1/parse-tasks` | 创建证据解析任务 |
| 查询解析结果 | GET | `/ocr-api/v1/parse-tasks/{task_id}` | 查询 OCR / 文档解析结果 |

---

## 9. 数据库与数据模型设计

### 9.1 PostgreSQL 表清单

| 表名 | 作用 |
|---|---|
| `fulfillment_case` | 履约问题或售后纠纷主对象 |
| `evidence_dossier` | 证据卷宗 |
| `evidence_item` | 证据条目 |
| `party_claim` | 用户 / 商家主张 |
| `issue` | 争议焦点 |
| `claim_issue_evidence_matrix` | 主张—争点—证据矩阵 |
| `evidence_request` | 补证请求 |
| `party_submission` | 双方补证提交 |
| `hearing_state` | C 层审理状态 |
| `hearing_record` | 庭审过程记录 |
| `adjudication_draft` | 裁决草案 |
| `remedy_plan` | 执行方案 |
| `review_packet` | 平台审核包 |
| `review_task` | 审核任务 |
| `approval_record` | 平台审核记录 |
| `action_record` | 实际执行动作记录 |
| `audit_log` | 审计日志 |
| `policy_rule` | 平台 / 商家 / 类目规则 |
| `evaluation_trace` | 离线评估轨迹 |

### 9.2 表设计原则

- 小写蛇形表名和字段名。
- 主键使用字符串 ID 或 UUID。
- 所有业务表包含 `created_at`、`updated_at`。
- 关键表包含 `created_by`、`updated_by`。
- 状态字段使用枚举字符串。
- 复杂结构使用 `jsonb`。
- 高频查询字段建索引。
- 业务唯一键加唯一约束。
- 金额使用 `numeric(18,2)`。
- 时间使用带时区类型。

### 9.3 核心字段摘要

`fulfillment_case`：

- `id`
- `order_id`
- `after_sale_id`
- `user_id`
- `merchant_id`
- `case_type`
- `dispute_type`
- `case_status`
- `route_type`
- `risk_level`
- `current_workflow_id`
- `title`
- `description`
- `created_at`
- `updated_at`
- `closed_at`

`evidence_item`：

- `id`
- `case_id`
- `dossier_id`
- `evidence_type`
- `source_type`
- `submitted_by_role`
- `submitted_by_id`
- `file_bucket`
- `file_object_key`
- `file_hash`
- `parsed_text`
- `parse_status`
- `visibility`
- `desensitized`
- `metadata_json`

`adjudication_draft`：

- `id`
- `case_id`
- `fact_findings_json`
- `evidence_assessment_json`
- `policy_application_json`
- `recommended_decision`
- `confidence`
- `reviewer_attention_json`
- `draft_text`
- `created_by_agent`

`action_record`：

- `id`
- `case_id`
- `plan_id`
- `action_type`
- `idempotency_key`
- `approved_by`
- `executed_by`
- `request_json`
- `result_json`
- `execution_status`
- `error_code`
- `error_message`
- `execution_time`

### 9.4 Elasticsearch 索引

| 索引 | 用途 |
|---|---|
| `policy_index` | 平台政策、商家政策、类目规则、举证责任规则检索 |
| `evidence_index` | 证据文本、聊天截图、物流证明、质检记录、序列号检索 |
| `case_index` | 历史 case 和相似争议案例检索 |

### 9.5 Redis Key

```text
dispute:session:{session_id}
dispute:case:{case_id}:state
dispute:lock:{idempotency_key}
dispute:rate-limit:{user_id}:{api}
dispute:agent-context:{case_id}
```

Redis 不存核心业务结果、不存审计日志、不存大文件。

### 9.6 MinIO Bucket

```text
evidence-original
evidence-desensitized
ocr-temp
policy-files
review-exports
```

---

## 10. 核心业务流程设计

### 10.1 普通履约问题流程

```mermaid
flowchart TD
    A[用户输入普通履约问题] --> B[Case Intake Agent]
    B --> C[创建 Fulfillment Case]
    C --> D[Evidence Dossier Builder]
    D --> E[Dispute Router 判断普通履约流]
    E --> F[Regular Fulfillment Flow]
    F --> G[Remedy Planner]
    G --> H[Approval Policy Engine]
    H --> I[Platform Human Review]
    I --> J[Tool Executor]
    J --> K[Case Closure]
    K --> L[Evaluation Trace]
```

验收标准：

- 查物流 case 能闭环。
- 不进入 C 层。
- 仍经过 D、审批、人审、执行。
- 全部动作可审计。

### 10.2 明确规则问题流程

```mermaid
flowchart TD
    A[用户或商家发起明确规则售后] --> B[Case Intake Agent]
    B --> C[Evidence Dossier Builder]
    C --> D[Dispute Router 判断明确规则流]
    D --> E[Rule-based Resolution Flow]
    E --> F[Remedy Planner]
    F --> G[Approval Policy Engine]
    G --> H[Platform Human Review]
    H --> I[Tool Executor]
    I --> J[Case Closure]
```

验收标准：

- 规则结论引用政策版本。
- 退款动作必须人审。
- 执行结果可查询。

### 10.3 争议审理流程

```mermaid
flowchart TD
    A[用户或商家发起争议] --> B[Case Intake Agent]
    B --> C[Evidence Dossier Builder]
    C --> D[Dispute Router 判断争议审理流]
    D --> E[C0 Hearing Controller]
    E --> F[C1 归纳争点]
    F --> G[C2 识别缺证]
    G --> H{是否需要补证}
    H -- 是 --> I[C3 请求双方补证]
    I --> J[Temporal 等待提交]
    J --> K[用户/商家提交材料]
    K --> L[B 更新证据卷宗]
    L --> M[C4 证据交叉检查]
    H -- 否 --> M
    M --> N{是否继续补证}
    N -- 是 --> G
    N -- 否 --> O[C5 规则适用]
    O --> P[C6 生成裁决草案]
    P --> Q[Remedy Planner]
    Q --> R[Approval Policy Engine]
    R --> S[Platform Human Review]
    S --> T{审核结果}
    T -- 要求补证 --> G
    T -- 批准执行 --> U[Tool Executor]
    T -- 驳回/转人工 --> V[人工处理或关闭]
    U --> W[Case Closure]
```

验收标准：

- 七天无理由掉包争议可跑通。
- 签收未收到争议可跑通。
- 可等待补证、恢复、超时。
- 可生成裁决草案和审核包。
- 人审通过后才能执行。

---

## 11. 配置管理规范

### 11.1 环境

```text
local
dev
test
uat
prod
```

### 11.2 配置文件

Java：

```text
application.yml
application-local.yml
application-dev.yml
application-test.yml
application-uat.yml
application-prod.yml
```

Python：

```text
.env
.env.local
.env.dev
.env.test
.env.uat
.env.prod
```

前端：

```text
.env
.env.local
.env.development
.env.test
.env.production
```

### 11.3 环境变量

统一前缀：

```text
DISPUTE_
```

示例：

```text
DISPUTE_POSTGRES_HOST=postgresql
DISPUTE_REDIS_HOST=redis
DISPUTE_MINIO_ENDPOINT=http://minio:9000
DISPUTE_ELASTICSEARCH_URL=http://elasticsearch:9200
DISPUTE_AGENT_SERVICE_URL=http://python-agent-service:8000
DISPUTE_OCR_SERVICE_URL=http://ocr-parser-service:8010
DISPUTE_LITELLM_BASE_URL=http://litellm-proxy:4000
DISPUTE_LANGFUSE_HOST=http://langfuse:3000
DISPUTE_TEMPORAL_ADDRESS=temporal-server:7233
```

### 11.4 敏感配置

不得提交真实密钥；`.env.example` 只放示例；Docker Compose 用环境变量注入；日志不得打印密钥。

### 11.5 Feature Flag

```text
feature.agent.intake.enabled=true
feature.agent.hearing.enabled=true
feature.agent.evaluation.enabled=true
feature.ocr.enabled=true
feature.human-review.required=true
feature.tool-executor.simulation=true
feature.auto-close.enabled=true
```

`feature.human-review.required` 正式版不得关闭。

---

## 12. 异常处理与错误码规范

### 12.1 异常类型

| 异常类型 | 异常类 |
|---|---|
| 参数异常 | `BadRequestException` |
| 鉴权异常 | `UnauthorizedException` |
| 权限异常 | `ForbiddenException` |
| 资源不存在 | `NotFoundException` |
| 业务异常 | `BusinessException` |
| 幂等冲突 | `IdempotencyConflictException` |
| 外部服务异常 | `ExternalServiceException` |
| 数据库异常 | `DatabaseException` |
| Workflow 异常 | `WorkflowExecutionException` |
| Agent 异常 | `AgentExecutionException` |
| Tool 异常 | `ToolExecutionException` |
| 超时异常 | `TimeoutException` |
| 审批异常 | `ApprovalException` |

### 12.2 错误码

| 错误码 | HTTP | 说明 |
|---|---:|---|
| `INVALID_ARGUMENT` | 400 | 参数错误 |
| `UNAUTHORIZED` | 401 | 未登录 |
| `FORBIDDEN` | 403 | 无权限 |
| `CASE_NOT_FOUND` | 404 | case 不存在 |
| `ORDER_NOT_FOUND` | 404 | 订单不存在 |
| `EVIDENCE_NOT_FOUND` | 404 | 证据不存在 |
| `CASE_STATUS_INVALID` | 409 | case 状态不允许 |
| `CASE_DUPLICATED` | 409 | 重复创建 |
| `IDEMPOTENCY_CONFLICT` | 409 | 幂等冲突 |
| `EVIDENCE_UPLOAD_FAILED` | 500 | 上传失败 |
| `EVIDENCE_PARSE_FAILED` | 500 | 解析失败 |
| `AGENT_SERVICE_UNAVAILABLE` | 503 | Agent 不可用 |
| `AGENT_OUTPUT_SCHEMA_INVALID` | 500 | Agent 输出不合法 |
| `WORKFLOW_START_FAILED` | 500 | Workflow 启动失败 |
| `WORKFLOW_SIGNAL_FAILED` | 500 | Workflow Signal 失败 |
| `APPROVAL_REQUIRED` | 409 | 需要审批 |
| `TOOL_EXECUTION_DENIED` | 403 | Tool 执行拒绝 |
| `TOOL_EXECUTION_FAILED` | 500 | Tool 执行失败 |
| `EXTERNAL_SERVICE_TIMEOUT` | 504 | 外部服务超时 |

### 12.3 处理要求

- Controller 不捕获业务异常，由全局异常处理器统一处理。
- Application 抛出明确业务异常。
- Infrastructure 异常转换为上层可理解异常。
- Tool 执行异常必须落库。
- 所有异常响应包含 `request_id` 和 `trace_id`。

---

## 13. 日志、Trace 与可观测性规范

### 13.1 日志字段

```json
{
  "timestamp": "...",
  "level": "INFO",
  "service": "java-api-service",
  "trace_id": "TRACE_xxx",
  "request_id": "REQ_xxx",
  "case_id": "CASE_xxx",
  "workflow_id": "WF_xxx",
  "user_id": "USER_xxx",
  "role": "PLATFORM_REVIEWER",
  "action": "CREATE_REMEDY_PLAN",
  "message": "..."
}
```

### 13.2 必须记录

- case 状态变化。
- route decision。
- hearing state。
- review action。
- tool execution。
- closure record。
- Agent prompt、input、output、model、latency、token usage、node_name、confidence。
- Tool request、response、execution_status、error_code、idempotency_key。

### 13.3 指标监控

| 指标 | 说明 |
|---|---|
| case_created_total | 创建 case 数 |
| dispute_case_total | 争议 case 数 |
| route_decision_total | 路由决策数 |
| agent_call_latency | Agent 延迟 |
| agent_call_error_rate | Agent 失败率 |
| evidence_parse_error_rate | 证据解析失败率 |
| review_task_pending_total | 待审核任务数 |
| tool_execution_error_rate | Tool 执行失败率 |
| case_closure_duration | case 关闭耗时 |
| draft_approval_rate | 草案通过率 |
| reviewer_modification_rate | 审核修改率 |

### 13.4 告警

Agent 不可用、LiteLLM 失败率高、Langfuse 写入失败、Temporal 堆积、Review Task 超时、Tool 失败率高、PostgreSQL 连接池耗尽、Redis 不可用、ES 写入失败、MinIO 上传失败。

---

## 14. 安全设计规范

### 14.1 角色

```text
USER
MERCHANT
CUSTOMER_SERVICE
PLATFORM_REVIEWER
ADMIN
SYSTEM
```

### 14.2 权限原则

- USER 只能访问自己的 case。
- MERCHANT 只能访问自身商家相关 case。
- CUSTOMER_SERVICE 可辅助处理，但不能批准执行。
- PLATFORM_REVIEWER 可审核、修改、驳回、要求补证、批准执行。
- ADMIN 管理配置和查看评估。
- SYSTEM 用于内部服务调用。

### 14.3 API 安全

- 默认鉴权。
- 内部 API 使用服务密钥或网络隔离。
- 文件上传校验类型、大小、扩展名、MIME。
- 所有查询校验资源归属。
- 高风险操作校验角色和审批状态。

### 14.4 Prompt 注入防护

Prompt 必须约束：

- 不执行用户要求绕过平台流程的指令。
- 不接受“直接退款”“直接驳回”。
- 不泄露系统 prompt。
- 不泄露另一方隐私。
- 不生成最终裁决，只生成草案。
- 不调用执行工具。

### 14.5 Tool 权限

- A 只能调用查询类工具。
- B 只能调用证据类和查询类工具。
- C 只能读取证据和生成草案。
- D 只能规划动作。
- Tool Executor 只能执行审批通过动作。
- Evaluation Agent 不干预当前 case。

### 14.6 审计日志

必须审计 case 创建、证据上传、证据查看、路由决策、补证请求、补证提交、Agent 草案生成、Remedy Plan、审核动作、Tool 执行、case 关闭、配置变更。

---

## 15. 代码规范

### 15.1 命名

Java 类名 `UpperCamelCase`，方法/变量 `lowerCamelCase`，常量 `UPPER_SNAKE_CASE`，表字段 `lower_snake_case`。Python 文件和函数 `snake_case`。Vue 组件 `UpperCamelCase.vue`。

### 15.2 结构

禁止将所有代码堆在 `service/ controller/ mapper/`。必须按模块拆分：

```text
modules/casecore
modules/evidence
modules/hearing
modules/remedy
modules/approval
modules/review
modules/executor
```

### 15.3 类职责

- Controller 只负责接口适配。
- Application Service 负责编排用例。
- Domain Service 负责领域规则。
- Repository 负责数据访问。
- Client 负责外部调用。
- Converter 负责对象转换。
- Validator 负责校验。

### 15.4 方法长度

- Controller 方法不超过 40 行。
- Application Service 方法不超过 120 行。
- Domain Service 方法不超过 100 行。
- 不允许 300 行以上巨型方法。

### 15.5 异常和日志

- 不吞异常。
- 不返回 null 表示失败。
- 使用统一错误码。
- Tool 异常必须落库。
- 不打印完整敏感信息。

### 15.6 DTO / Entity 转换

- Entity 不直接返回前端。
- Controller 不直接操作 Entity。
- 使用 Converter / Mapper。
- 敏感字段在转换层脱敏。

### 15.7 枚举

必须定义：

- `CaseStatus`
- `CaseType`
- `DisputeType`
- `RouteType`
- `RiskLevel`
- `HearingStage`
- `HearingStatus`
- `ReviewDecision`
- `ActionType`
- `ExecutionStatus`
- `EvidenceType`
- `PartyType`

### 15.8 禁止事项

- 不把正式版降级成 MVP。
- 不删除人审节点。
- 不让 Agent 直接退款、补发、关闭售后。
- 不让 C 层直接调用执行工具。
- 不让 D 层重新判断事实。
- 不让 Tool Executor 跳过审批。
- 不加入申诉 / 复审。
- 不引入禁止技术。
- 不在 Controller 写业务逻辑。
- 不在前端写裁决逻辑。
- 不泄露敏感信息。
- 不跳过测试。

---

## 16. 测试方案

### 16.1 单元测试

Java：

- Case 状态机。
- Router 路由规则。
- Remedy Planner。
- Approval Policy Engine。
- Tool Executor 幂等。
- 权限判断。
- DTO Converter。

Python：

- Pydantic schema。
- C1-C6 节点输出。
- Prompt 输入构造。
- LangGraph 路径。
- LLM mock 返回解析。
- 非法输出处理。

Frontend：

- 表单校验。
- 审核按钮权限。
- 文件上传。
- case 状态展示。
- Remedy Plan 展示。

### 16.2 集成测试

- Java API + PostgreSQL。
- Java API + Redis。
- Java API + MinIO。
- Java API + Elasticsearch。
- Java API + Python Agent Service。
- Java API + OCR Parser Service。
- Temporal Workflow + Activity。

### 16.3 API 测试

覆盖创建 case、查询 case、上传证据、构建 dossier、路由、启动 workflow、查询 hearing、提交补证、查询审核包、提交审核、执行动作、关闭 case、查询 evaluation。

### 16.4 Workflow 测试

覆盖普通履约流、明确规则流、争议审理流、补证等待、补证超时、人审等待、人审要求补证、Tool 执行失败、Workflow 重试恢复。

### 16.5 Agent 测试

覆盖 Intake、C1、C2、C4、C5、C6、低置信度标记人工审核、Prompt 注入防护。

### 16.6 Tool 测试

- 未审批退款被拒绝。
- 已审批退款可执行。
- 重复退款被幂等拦截。
- 补发库存不足失败。
- 关闭售后状态冲突失败。
- 通知失败可重试。

### 16.7 权限测试

用户不能访问他人 case；商家不能访问其他商家 case；客服不能批准执行；审核员可审核；SYSTEM 可调用内部执行接口；Agent 不能调用执行接口。

### 16.8 回归测试

每次合并回归：

- 普通履约 E2E。
- 明确规则 E2E。
- 七天无理由掉包争议 E2E。
- 签收未收到争议 E2E。
- 审核员要求补证 E2E。
- Tool Executor 幂等 E2E。

### 16.9 性能测试

- 创建 case API P95 < 500ms。
- 查询 case API P95 < 300ms。
- 上传证据元数据 API P95 < 800ms。
- Agent C 层异步执行，不阻塞前端。
- 审核台列表 P95 < 500ms。

### 16.10 部署验证

Docker Compose 启动后验证 Nginx、Frontend、Java、Python Agent、OCR、PostgreSQL、Redis、MinIO、Elasticsearch、Temporal、Langfuse、LiteLLM 全部健康。

---

## 17. 部署方案

### 17.1 本地开发部署

```bash
cp .env.example .env
./scripts/dev-up.sh
./scripts/init-db.sh
./scripts/init-minio.sh
./scripts/init-es.sh
./scripts/smoke-test.sh
```

停止：

```bash
./scripts/dev-down.sh
```

重置：

```bash
./scripts/dev-reset.sh
```

### 17.2 Docker Compose 服务

```yaml
services:
  frontend:
  java-api-service:
  python-agent-service:
  ocr-parser-service:
  temporal-server:
  postgresql:
  redis:
  elasticsearch:
  minio:
  litellm-proxy:
  langfuse:
  nginx:
```

### 17.3 启动顺序

```text
postgresql
redis
minio
elasticsearch
temporal-server
langfuse
litellm-proxy
python-agent-service
ocr-parser-service
java-api-service
frontend
nginx
```

### 17.4 端口规划

| 服务 | 内部端口 | 本地端口 |
|---|---:|---:|
| nginx | 80 | 8080 |
| frontend | 5173 | 5173 |
| java-api-service | 8080 | 18080 |
| python-agent-service | 8000 | 18000 |
| ocr-parser-service | 8010 | 18010 |
| temporal-server | 7233 | 7233 |
| postgresql | 5432 | 15432 |
| redis | 6379 | 16379 |
| elasticsearch | 9200 | 19200 |
| minio api | 9000 | 19000 |
| minio console | 9001 | 19001 |
| litellm-proxy | 4000 | 14000 |
| langfuse | 3000 | 13000 |

### 17.5 初始化

Flyway migration：

```text
V001__init_case_tables.sql
V002__init_evidence_tables.sql
V003__init_hearing_tables.sql
V004__init_review_executor_tables.sql
V005__init_policy_audit_tables.sql
```

MinIO bucket：

```text
evidence-original
evidence-desensitized
ocr-temp
policy-files
review-exports
```

Elasticsearch index：

```text
policy_index
evidence_index
case_index
```

### 17.6 健康检查

Java：

```text
GET /actuator/health
```

Python：

```text
GET /health
```

OCR：

```text
GET /health
```

Docker Compose 建议使用：

```yaml
restart: unless-stopped
```

### 17.7 未来演进

当前不使用 Kubernetes。未来可演进到 K3s / Kubernetes、Ingress、Secret / ConfigMap、托管 PostgreSQL、云对象存储、独立规则服务、ES 集群、Langfuse 独立部署。

---

## 18. CI/CD 与工程质量保障

### 18.1 分支规范

```text
main
develop
feature/{module-name}
fix/{issue-name}
release/{version}
hotfix/{issue-name}
```

### 18.2 Commit Message

```text
type(scope): subject
```

类型：

```text
feat
fix
docs
style
refactor
test
chore
build
ci
```

示例：

```text
feat(hearing): add issue framing agent schema
fix(executor): prevent unapproved refund execution
test(router): add dispute route cases
```

### 18.3 PR 规范

PR 必须包含：

- 变更说明。
- 影响模块。
- 测试结果。
- 是否修改数据库。
- 是否修改 API。
- 是否修改 Prompt。
- 是否影响安全 / 权限。
- 截图或接口示例。

### 18.4 Code Review 重点

- 是否违反 Agent 不直接执行原则。
- 是否绕过人审。
- 是否引入禁止技术。
- 是否破坏分层。
- 是否存在大类、大方法。
- 是否缺少测试。
- 是否泄露敏感信息。
- 是否缺少幂等。
- 是否缺少审计日志。

### 18.5 自动化检查

```text
frontend lint
frontend test
java mvn test
java integration test
python pytest
ocr pytest
docker compose config check
openapi validation
```

### 18.6 构建流程

```text
build frontend
build java-api-service jar
build python-agent-service image
build ocr-parser-service image
build docker images
run smoke tests
```

### 18.7 回滚

- 代码回滚到上一 tag。
- migration 提供回滚说明。
- Prompt 版本可回滚。
- Docker image 使用上个稳定版本。
- Tool Executor 动作不可简单回滚，必须补偿处理。

---

## 19. Codex 开发任务拆解

### Phase 1：工程骨架初始化

目标：创建 monorepo、基础目录、配置文件、Docker Compose 骨架和文档目录。

文件范围：

```text
README.md
.gitignore
.editorconfig
.env.example
docker-compose.yml
docs/
deploy/
scripts/
frontend/
java-api-service/
python-agent-service/
ocr-parser-service/
tests/
```

任务：

- 初始化仓库结构。
- 创建基础 README。
- 创建 `.env.example`。
- 创建 Docker Compose 初版。
- 创建 scripts 占位脚本。
- 创建 docs 目录。

禁止：不引入 Kubernetes、Kafka、MCP；不写简化版单体脚本。

验收：目录完整；`docker compose config` 通过；README 说明服务边界。

### Phase 2：Java Spring Boot 基础工程

目标：创建 Java 21 + Spring Boot 3 后端基础工程。

任务：

- 初始化 Maven Spring Boot。
- 建立 common、config、modules、integration。
- 实现 ApiResponse、ErrorCode、BusinessException、GlobalExceptionHandler。
- 实现 TraceIdFilter。
- 配置 PostgreSQL、Redis、MinIO、ES、Temporal、OpenAPI。
- 提供 `/actuator/health`。

验收：`mvn test` 通过；应用可启动；异常响应统一。

### Phase 3：数据库模型与 Migration

目标：创建核心表结构、Entity、Repository 和枚举。

任务：

- 创建 case、evidence、issue、matrix、hearing、draft、remedy、review、approval、action、audit、policy、evaluation 表。
- 创建核心枚举。
- 创建 Repository。

禁止：不省略 Review Packet、Action Record、Evaluation Trace。

验收：Flyway migration 可执行；Repository 测试通过。

### Phase 4：Case Intake 与 Case 管理模块

目标：实现 case 创建、查询、状态管理、Intake 结果落库。

任务：

- 创建 case API。
- 查询 case API。
- Intake Application Service。
- AgentServiceClient。
- 缺槽状态。
- 测试。

禁止：Intake 不裁决、不承诺退款。

验收：普通履约、争议 case 可创建；缺失订单号可处理；Agent 失败可降级。

### Phase 5：Evidence Dossier 与文件证据模块

目标：实现证据上传、MinIO、元数据、dossier、时间线、矩阵。

任务：

- 证据上传 API。
- MinIO 存储。
- Evidence Metadata。
- Dossier 构建。
- 时间线。
- OCR 任务触发。
- ES 写入。

禁止：B 层不得裁决，不得隐藏证据。

验收：文件可上传；元数据落库；dossier 可查询；OCR 失败不阻塞。

### Phase 6：OCR Parser Service

目标：实现 Python OCR / 文件解析服务。

任务：

- FastAPI。
- parse task API。
- PaddleOCR。
- MarkItDown。
- 读取 MinIO。
- 写回解析结果。
- 写入 ES。
- 测试。

验收：图片、PDF、Word、Excel 可解析，结果可检索。

### Phase 7：Dispute Router、Regular Flow、Rule Flow

目标：实现路由和两条非 C 层流程。

任务：

- Router。
- 普通履约处理结论。
- 明确规则处理结论。
- 政策表和政策查询。
- 路由 API。
- 测试。

禁止：Router 不结束 case；Rule Flow 不绕过 D 和人审。

验收：三类路径可路由；结果可审计。

### Phase 8：Python Agent Service 与 C1-C6

目标：实现 FastAPI + LangGraph C 层 Agent Workflow。

任务：

- FastAPI。
- Pydantic schema。
- LangGraph hearing graph。
- C1-C6 agents。
- LiteLLM。
- Langfuse。
- Prompt 文件。
- 测试。

禁止：Agent 不输出最终裁决，不调用执行工具。

验收：C1-C6 可执行；输出结构化裁决草案；Langfuse 有 trace。

### Phase 9：Temporal Workflow 与 Hearing Controller

目标：实现 Java Temporal Workflow 控制全局 case 和 C 层补证循环。

任务：

- CaseWorkflow。
- Activities。
- Workflow 启动。
- 用户 / 商家 / 审核员 Signal。
- 超时处理。
- hearing_state、hearing_record 落库。

禁止：不把长流程写成同步 Controller；不让 Python 控制全局状态。

验收：Workflow 可启动、暂停、Signal 恢复、超时、进入 D。

### Phase 10：Remedy Planner

目标：实现 D 层执行方案规划。

任务：

- RemedyPlan。
- 从 Regular / Rule / Draft 映射。
- 通知方案。
- 动作前置条件。
- 测试。

禁止：不重新断案，不直接执行，不绕过审批。

验收：三条路径都可生成 Remedy Plan；动作有风险等级和幂等键。

### Phase 11：Approval Policy Engine 与 Review

目标：实现审批规则、人审任务、审核包和审核动作。

任务：

- 审批规则配置。
- ApprovalDecision。
- Review Task。
- Review Packet。
- 审核任务 API。
- 审核包 API。
- 审核提交 API。
- 前端审核台。

禁止：不把人审做成形式化自动通过；客服不得越权批准。

验收：审核员可确认、修改、驳回、补证；动作可审计；批准后才能执行。

### Phase 12：Tool Executor

目标：实现确定性执行器和模拟 Tool API。

任务：

- 执行命令。
- 审批校验。
- 幂等校验。
- 退款、补发、关闭、驳回、通知模拟。
- ActionRecord。
- 测试。

禁止：不执行未审批动作，不修改草案和审核意见。

验收：未审批拒绝；审批动作可执行；重复执行拦截；失败有记录。

### Phase 13：Case Closure 与 Evaluation Agent

目标：实现 case 关闭和离线评估。

任务：

- case closure。
- Evaluation Trace。
- Python Evaluation Agent。
- Evaluation Report。
- 草案通过率、修改率统计。
- 测试。

禁止：Evaluation 不参与实时裁决，不自动修改规则或 prompt。

验收：case 可关闭；Evaluation Report 可生成；指标可查询。

### Phase 14：Frontend 全流程页面

目标：实现用户补证、商家补证、审核台、case 详情、证据卷宗、时间线、执行记录页面。

任务：

- 路由。
- API client。
- case 列表和详情。
- 证据上传。
- 补证页面。
- 审核台。
- 执行记录。
- 审计日志。

禁止：前端不写裁决逻辑，不硬编码审批规则。

验收：用户可补证；商家可补证；审核员可审核。

### Phase 15：Docker Compose 联调与部署验证

目标：完成本地一键启动和 E2E 联调。

任务：

- Dockerfile。
- Nginx。
- PostgreSQL。
- MinIO。
- Elasticsearch。
- LiteLLM。
- Langfuse。
- smoke test。
- 部署文档。

禁止：不引入 Kubernetes，不写死密钥，不省略健康检查。

验收：`dev-up.sh` 可启动全服务；health check 正常；E2E 通过。

### Phase 16：全项目 Review、测试补齐与正式验收

目标：完成代码规范、测试、文档、部署和验收 checklist。

任务：

- 全量测试。
- E2E。
- 架构覆盖检查。
- 安全边界检查。
- 日志审计检查。
- Prompt 检查。
- API / 部署文档检查。
- 修复规范问题。

禁止：不跳过测试，不以 TODO 代替正式实现。

验收：全量测试通过；全链路 E2E 通过；架构完整；本地一键启动。

---

## 20. Codex 执行提示词模板

### 20.1 初始化仓库结构提示词

```text
你现在作为 Codex，在当前空仓库中初始化“争议裁决驱动的人审门控订单履约协作系统”的正式版 monorepo 工程结构。

任务目标：
- 创建正式版仓库目录结构。
- 包含 frontend、java-api-service、python-agent-service、ocr-parser-service、docs、deploy、scripts、tests 等目录。
- 创建 README.md、.gitignore、.editorconfig、.env.example、docker-compose.yml 初版。
- 目录结构必须适合 Java + Python + Vue3 + Docker Compose 多服务开发。

修改范围：
- 只允许新增基础工程目录和基础配置文件。
- 不实现具体业务逻辑。

禁止事项：
- 不要生成 MVP 简化结构。
- 不要引入 Kubernetes、Kafka、MCP、Milvus、Qdrant、Drools、OPA。
- 不要把所有代码放在一个单体脚本里。
- 不要删除已有文件。

输出要求：
- 给出新增文件列表。
- 给出每个目录用途说明。
- docker-compose.yml service 名称必须包含 frontend、java-api-service、python-agent-service、ocr-parser-service、temporal-server、postgresql、redis、elasticsearch、minio、litellm-proxy、langfuse、nginx。

验收标准：
- 仓库结构完整。
- docker compose config 可通过。
- README 能说明项目定位和服务边界。
```

### 20.2 创建后端基础工程提示词

```text
你现在作为 Codex，在 java-api-service 下创建 Java 21 + Spring Boot 3 正式后端基础工程。

任务目标：
- 初始化 Maven Spring Boot 工程。
- 建立 common、config、modules、integration 分层结构。
- 实现统一 ApiResponse、ErrorCode、BusinessException、GlobalExceptionHandler。
- 实现 TraceIdFilter。
- 配置 PostgreSQL、Redis、MinIO、Elasticsearch、Temporal、OpenAPI。
- 提供 /actuator/health。

修改范围：
- 只修改 java-api-service 目录。

禁止事项：
- Controller 不写业务逻辑。
- 不实现具体争议裁决流程。
- 不引入技术清单外重型依赖。
- 不引入 MCP、Kafka、Milvus、Drools、OPA。

验收标准：
- mvn test 通过。
- 应用可启动。
- /actuator/health 可访问。
- 异常响应格式统一。
```

### 20.3 创建数据库模型提示词

```text
你现在作为 Codex，为订单履约争议裁决系统创建 PostgreSQL 数据库 migration、Entity、Repository 和核心枚举。

任务目标：
- 创建 fulfillment_case、evidence_dossier、evidence_item、party_claim、issue、claim_issue_evidence_matrix、evidence_request、party_submission、hearing_state、hearing_record、adjudication_draft、remedy_plan、review_packet、review_task、approval_record、action_record、audit_log、policy_rule、evaluation_trace 等表。
- 创建对应 Entity、Repository、核心枚举。
- 使用 Flyway migration。

禁止事项：
- 不省略 Review Packet、Action Record、Evaluation Trace。
- 不让 Entity 直接作为 API 返回对象。
- 不删除已有 migration。

验收标准：
- Flyway migration 能在空 PostgreSQL 上执行成功。
- Repository 测试通过。
- 核心枚举无魔法字符串。
```

### 20.4 创建核心业务模块提示词

```text
你现在作为 Codex，实现 Case Intake、Evidence Dossier、Dispute Router、Regular Flow、Rule-based Flow 的正式版业务模块。

任务目标：
- 实现 case 创建、查询、状态管理。
- 实现 Evidence Dossier 构建、证据上传元数据、时间线、主张和矩阵基础能力。
- 实现 Dispute Router，支持 REGULAR_FULFILLMENT、RULE_BASED_RESOLUTION、DISPUTE_HEARING 三种路由。
- 实现 Regular Fulfillment Flow。
- 实现 Rule-based Resolution Flow。
- 所有路径后续必须进入 Remedy Planner，不允许直接结束。

禁止事项：
- B 层不得裁决。
- Router 不得执行动作。
- Rule Flow 不得绕过 D、Approval、Human Review。
- 不允许 Agent 承诺退款、补发、驳回。
- 不允许 Controller 写业务逻辑。

验收标准：
- 普通履约 case 可创建并路由。
- 明确规则 case 可创建并路由。
- 争议 case 可进入争议审理路由。
- 所有关键状态变化写 audit_log。
```

### 20.5 创建 Agent / Workflow 模块提示词

```text
你现在作为 Codex，实现 Python FastAPI + LangGraph 的 C 层争议审理 Agent Workflow，以及 Java Temporal Hearing Controller 对接。

任务目标：
- 在 python-agent-service 中实现 C1-C6 Agent。
- 使用 LangGraph 编排 C1-C6。
- 使用 Pydantic 定义每个节点输入输出 schema。
- 使用 LiteLLM Proxy 调用模型。
- 使用 Langfuse 记录 Agent Trace。
- 在 Java 中实现调用 Python Agent Service 的 client。
- 在 Java Temporal Workflow 中实现 Hearing Controller、补证等待、超时、Signal 恢复。

禁止事项：
- C 层不得执行退款、补发、关闭售后。
- Agent 不得输出最终裁决，只能输出裁决草案。
- Prompt 不得硬编码在业务代码中。
- 不得跳过人审。
- 不得引入 MCP。

验收标准：
- C1-C6 可执行。
- 可生成结构化裁决草案。
- 可补证等待和恢复。
- Agent 输出写入 Langfuse。
- schema 校验失败能正确报错并进入人工重点审核。
```

### 20.6 创建 API 接口提示词

```text
你现在作为 Codex，为系统创建正式版 REST API。

任务目标：
- 实现 case、dossier、evidence、route、workflow、hearing、submission、review、execution、closure、evaluation 等 API。
- 所有响应使用 ApiResponse。
- 所有写操作支持 Idempotency-Key。
- 所有接口生成 OpenAPI 文档。

禁止事项：
- Controller 不写业务逻辑。
- 不直接返回 Entity。
- 不跳过权限校验。
- 不绕过审批执行动作。

验收标准：
- API 测试通过。
- OpenAPI 可访问。
- 错误响应统一。
```

### 20.7 创建测试用例提示词

```text
你现在作为 Codex，为订单履约争议裁决系统补齐正式版测试。

任务目标：
- 编写 Java 单元测试、集成测试、Workflow 测试。
- 编写 Python Agent Service 单元测试和集成测试。
- 编写 OCR Parser Service 测试。
- 编写前端基础测试。
- 编写 E2E 测试文档和 smoke test。
- 覆盖普通履约流、明确规则流、争议审理流、补证等待、人审、Tool Executor 幂等、case closure、evaluation。

禁止事项：
- 不允许删除已有测试。
- 不允许跳过失败测试。
- 不允许只测 happy path。
- 不允许 mock 掉所有核心逻辑。

验收标准：
- mvn test 通过。
- pytest 通过。
- 前端测试通过。
- smoke test 通过。
- E2E 三条主流程通过。
```

### 20.8 修复代码规范问题提示词

```text
你现在作为 Codex，对当前项目进行代码规范修复。

任务目标：
- 检查 Controller 是否写了业务逻辑。
- 检查 Entity 是否直接返回前端。
- 检查大类、大方法、魔法字符串、异常处理、日志、审计、幂等。
- 检查 Agent 是否可能绕过人审或执行工具。
- 检查是否引入技术清单外禁止技术。

禁止事项：
- 不删除测试。
- 不降低功能范围。
- 不把正式版简化成 MVP。
- 不绕过失败测试。

验收标准：
- 代码符合分层规范。
- 测试通过。
- 无新增架构违规。
```

### 20.9 生成部署文件提示词

```text
你现在作为 Codex，完善 Docker Compose 本地正式部署方案。

任务目标：
- 完善 docker-compose.yml。
- 为 frontend、java-api-service、python-agent-service、ocr-parser-service 创建 Dockerfile。
- 配置 postgresql、redis、elasticsearch、minio、temporal-server、litellm-proxy、langfuse、nginx。
- 创建 Nginx 反向代理配置。
- 创建 MinIO bucket 初始化脚本。
- 创建 Elasticsearch index 初始化脚本。
- 创建 dev-up、dev-down、dev-reset、smoke-test 脚本。
- 更新部署文档。

禁止事项：
- 不引入 Kubernetes。
- 不写死真实密钥。
- 不省略 health check。
- 不省略数据卷。

验收标准：
- docker compose config 通过。
- docker compose up 可启动。
- 所有 health check 正常。
- smoke test 通过。
```

### 20.10 全项目 Review 提示词

```text
你现在作为 Codex，对整个订单履约争议裁决系统进行正式版交付前 Review。

任务目标：
- 检查架构覆盖完整性。
- 检查 A/B/Router/C/D/Approval/Human Review/Tool Executor/Case Closure/Evaluation 是否全部实现。
- 检查技术栈是否符合清单。
- 检查是否引入禁止技术。
- 检查是否存在 MVP 化简化。
- 检查是否绕过人审。
- 检查 Agent 是否直接裁决或执行。
- 检查代码分层、异常、日志、审计、幂等、安全。
- 检查测试和部署文档。

禁止事项：
- 不修改无关文件。
- 不删除测试。
- 不降低功能范围。
- 不隐藏未完成问题。

验收标准：
- 所有 BLOCKER 修复。
- HIGH 问题有明确处理。
- 全量测试通过。
- Docker Compose 部署通过。
```

---

## 21. 最终验收清单

### 21.1 架构覆盖完整性

- [ ] A. Case Intake Agent。
- [ ] B. Evidence Dossier Builder。
- [ ] Dispute Router。
- [ ] Regular Fulfillment Flow。
- [ ] Rule-based Resolution Flow。
- [ ] C. Dispute Hearing Workflow。
- [ ] C0 Hearing Controller。
- [ ] C1 Issue Framing Agent。
- [ ] C2 Evidence Gap & Request Agent。
- [ ] C3 Party Liaison Agent。
- [ ] C4 Evidence Cross-check Agent。
- [ ] C5 Rule Application Agent。
- [ ] C6 Adjudication Draft Agent。
- [ ] D. Remedy Planner。
- [ ] Approval Policy Engine。
- [ ] Platform Human Review。
- [ ] Tool Executor。
- [ ] Case Closure。
- [ ] Evaluation Agent。
- [ ] 所有路径最终进入 D、审批、人审和执行层。
- [ ] 未实现申诉 / 复审流程。

### 21.2 功能完整性

- [ ] 普通履约流完整闭环。
- [ ] 明确规则流完整闭环。
- [ ] 争议审理流完整闭环。
- [ ] 用户可提交补证。
- [ ] 商家可提交补证。
- [ ] 审核员可查看审核包。
- [ ] 审核员可确认、修改、驳回、要求补证、转人工。
- [ ] Tool Executor 可执行审批通过动作。
- [ ] closed case 可进入 Evaluation。

### 21.3 代码规范

- [ ] Controller 不写业务逻辑。
- [ ] Service 不直接暴露数据库细节。
- [ ] Entity 不直接返回前端。
- [ ] DTO / VO / Entity / Domain Model 边界清晰。
- [ ] 外部 API 调用在 integration/client 层。
- [ ] 无大类、大方法。
- [ ] 无循环依赖。
- [ ] 无重复代码。
- [ ] 无魔法字符串。
- [ ] 枚举和常量集中管理。

### 21.4 API 可用性

- [ ] REST API 前缀统一。
- [ ] 响应格式统一。
- [ ] 错误码统一。
- [ ] OpenAPI 文档完整。
- [ ] 写操作支持幂等键。
- [ ] 分页接口规范。
- [ ] 鉴权校验完整。

### 21.5 数据库正确性

- [ ] 核心表全部创建。
- [ ] 索引完整。
- [ ] 唯一约束完整。
- [ ] 审计字段完整。
- [ ] 软删除策略明确。
- [ ] Flyway migration 可执行。
- [ ] 数据一致性策略明确。
- [ ] 事务边界合理。

### 21.6 Agent / Workflow 正确性

- [ ] Temporal Workflow 可启动、暂停、恢复、超时。
- [ ] LangGraph C1-C6 可执行。
- [ ] Agent 输出 schema 校验。
- [ ] Agent 不直接执行动作。
- [ ] C 层不输出最终裁决。
- [ ] 低置信度进入人工重点审核。
- [ ] Langfuse Trace 完整。

### 21.7 Tool Executor 正确性

- [ ] 未审批动作被拒绝。
- [ ] 审批通过动作可执行。
- [ ] 幂等键生效。
- [ ] 执行失败有记录。
- [ ] 执行结果可查询。
- [ ] 不修改裁决草案和审核意见。

### 21.8 测试覆盖

- [ ] Java 单元测试通过。
- [ ] Java 集成测试通过。
- [ ] Temporal Workflow 测试通过。
- [ ] Python Agent 测试通过。
- [ ] OCR Parser 测试通过。
- [ ] Frontend 测试通过。
- [ ] API 测试通过。
- [ ] E2E 测试通过。
- [ ] 性能测试完成。
- [ ] 部署验证测试完成。

### 21.9 异常处理

- [ ] 参数异常统一处理。
- [ ] 鉴权异常统一处理。
- [ ] 权限异常统一处理。
- [ ] 外部服务异常统一处理。
- [ ] 数据库异常统一处理。
- [ ] Workflow 异常统一处理。
- [ ] Agent 异常统一处理。
- [ ] Tool 异常统一处理。
- [ ] 超时异常统一处理。
- [ ] 幂等冲突统一处理。

### 21.10 日志与 Trace

- [ ] 所有请求有 trace_id。
- [ ] 所有关键状态变化有日志。
- [ ] 所有审核动作有审计。
- [ ] 所有执行动作有 ActionRecord。
- [ ] Agent Trace 写入 Langfuse。
- [ ] Hearing Record 写入 PostgreSQL。
- [ ] 敏感信息已脱敏。
- [ ] 慢请求有记录。
- [ ] 告警指标明确。

### 21.11 安全

- [ ] 用户不能越权访问 case。
- [ ] 商家不能越权访问 case。
- [ ] 客服不能越权批准执行。
- [ ] Agent 不能调用执行工具。
- [ ] Tool Executor 校验审批。
- [ ] Prompt 注入防护已实现。
- [ ] 文件上传校验完整。
- [ ] 日志脱敏。
- [ ] 密钥不入库、不入日志、不提交仓库。
- [ ] 审计日志不可绕过。

### 21.12 部署

- [ ] Docker Compose 包含所有服务。
- [ ] 服务启动顺序正确。
- [ ] 环境变量完整。
- [ ] PostgreSQL 初始化成功。
- [ ] Redis 可用。
- [ ] MinIO bucket 初始化成功。
- [ ] Elasticsearch index 初始化成功。
- [ ] Temporal 可用。
- [ ] Langfuse 可用。
- [ ] LiteLLM 可用。
- [ ] Nginx 代理正常。
- [ ] 健康检查正常。
- [ ] smoke test 通过。

### 21.13 文档

- [ ] README 完整。
- [ ] 架构文档完整。
- [ ] API 文档完整。
- [ ] 数据库文档完整。
- [ ] 部署文档完整。
- [ ] Codex 开发文档完整。
- [ ] 测试文档完整。
- [ ] 安全文档完整。
- [ ] 代码规范文档完整。

### 21.14 可维护性

- [ ] 模块边界清晰。
- [ ] 服务边界清晰。
- [ ] 代码分层清晰。
- [ ] 配置集中管理。
- [ ] 依赖可控。
- [ ] 不存在技术清单外重型依赖。
- [ ] 可本地完整启动。
- [ ] 当前不依赖 Kubernetes。
- [ ] 可后续替换真实订单、物流、支付、退款、补发系统 Adapter。
- [ ] 可后续扩展向量检索、规则引擎、微服务拆分，但当前不强依赖。

---

## 附录 A：上次回答内容覆盖核验清单

### A.1 21 个主章节覆盖核验

| 上次回答章节 | 当前 MD 是否覆盖 | 位置 |
|---|---|---|
| 1. 文档说明 | 已覆盖 | 第 1 章 |
| 2. 项目背景与建设目标 | 已覆盖 | 第 2 章 |
| 3. 总体架构理解 | 已覆盖 | 第 3 章 |
| 4. 正式版功能范围 | 已覆盖 | 第 4 章 |
| 5. 仓库结构设计 | 已覆盖 | 第 5 章 |
| 6. 后端工程结构规范 | 已覆盖 | 第 6 章 |
| 7. Agent / Workflow / Tool 工程设计 | 已覆盖 | 第 7 章 |
| 8. API 接口设计规范 | 已覆盖 | 第 8 章 |
| 9. 数据库与数据模型设计 | 已覆盖 | 第 9 章 |
| 10. 核心业务流程设计 | 已覆盖 | 第 10 章 |
| 11. 配置管理规范 | 已覆盖 | 第 11 章 |
| 12. 异常处理与错误码规范 | 已覆盖 | 第 12 章 |
| 13. 日志、Trace 与可观测性规范 | 已覆盖 | 第 13 章 |
| 14. 安全设计规范 | 已覆盖 | 第 14 章 |
| 15. 代码规范 | 已覆盖 | 第 15 章 |
| 16. 测试方案 | 已覆盖 | 第 16 章 |
| 17. 部署方案 | 已覆盖 | 第 17 章 |
| 18. CI/CD 与工程质量保障 | 已覆盖 | 第 18 章 |
| 19. Codex 开发任务拆解 | 已覆盖 | 第 19 章 |
| 20. Codex 执行提示词模板 | 已覆盖 | 第 20 章 |
| 21. 最终验收清单 | 已覆盖 | 第 21 章 |

### A.2 架构模块覆盖核验

| 架构模块 | 当前 MD 是否覆盖 |
|---|---|
| Case Intake Agent | 已覆盖 |
| Evidence Dossier Builder | 已覆盖 |
| Dispute Router | 已覆盖 |
| Regular Fulfillment Flow | 已覆盖 |
| Rule-based Resolution Flow | 已覆盖 |
| Dispute Hearing Workflow | 已覆盖 |
| C0 Hearing Controller | 已覆盖 |
| C1 Issue Framing Agent | 已覆盖 |
| C2 Evidence Gap & Request Agent | 已覆盖 |
| C3 Party Liaison Agent | 已覆盖 |
| C4 Evidence Cross-check Agent | 已覆盖 |
| C5 Rule Application Agent | 已覆盖 |
| C6 Adjudication Draft Agent | 已覆盖 |
| Remedy Planner | 已覆盖 |
| Approval Policy Engine | 已覆盖 |
| Platform Human Review | 已覆盖 |
| Tool Executor | 已覆盖 |
| Case Closure | 已覆盖 |
| Evaluation Agent | 已覆盖 |

### A.3 技术清单覆盖核验

| 技术 / 服务 | 当前 MD 是否覆盖 |
|---|---|
| Docker Compose | 已覆盖 |
| Vue3 + Vite + Element Plus | 已覆盖 |
| Java 21 + Spring Boot 3 | 已覆盖 |
| Temporal + Java SDK | 已覆盖 |
| Python FastAPI + LangGraph | 已覆盖 |
| REST API 通信 | 已覆盖 |
| LiteLLM Proxy | 已覆盖 |
| Langfuse | 已覆盖 |
| PostgreSQL | 已覆盖 |
| Redis | 已覆盖 |
| MinIO | 已覆盖 |
| Elasticsearch | 已覆盖 |
| PaddleOCR | 已覆盖 |
| MarkItDown | 已覆盖 |
| Spring Boot 内置规则服务 + 配置表 | 已覆盖 |
| Spring Boot Tool API | 已覆盖 |
| Nginx | 已覆盖 |

### A.4 禁止事项覆盖核验

| 禁止事项 | 当前 MD 是否明确 |
|---|---|
| 不做 MVP | 已明确 |
| 不做申诉 / 复审 | 已明确 |
| Agent 不直接当最终法官 | 已明确 |
| C 层不直接执行退款、补发、关闭售后 | 已明确 |
| D 层不重新断案 | 已明确 |
| Tool Executor 不执行未审批动作 | 已明确 |
| 当前不引入 MCP | 已明确 |
| 当前不引入 Kafka | 已明确 |
| 当前不引入 Milvus / Qdrant | 已明确 |
| 当前不引入 OPA / Drools | 已明确 |
| 当前不引入 Kubernetes / K3s / Helm | 已明确 |
| 当前不引入完整微服务拆分 | 已明确 |
| 当前不自部署大模型 | 已明确 |

### A.5 核验结论

本 Markdown 文件已覆盖上次回答中的全部 21 个主章节、全部核心业务模块、全部技术选型、全部工程规范、全部 Codex 开发阶段、全部 Codex 提示词模板和最终验收清单。

当前文件可作为正式版开发主控文档直接交给 Codex 使用。
