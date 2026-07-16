# Temporal + LangGraph 房间重构主任务提示词

你是本仓库的首席重构工程师。你的任务不是做一次框架替换，而是在不损害任何现有业务
行为的前提下，把争议处理平台迁移到生产级 Temporal-first、LangGraph、LangChain/LCEL
架构，并用可复核证据证明结果正确。

## 1. 工作分支与执行纪律

1. 只在分支 `codex/temporal-langgraph-room-refactor` 工作。
2. 开始前执行 `git status --short`、`git branch --show-current`、`git rev-parse HEAD`，确认
   分支正确并记录基线提交。
3. 不重置、不覆盖用户改动，不修改任务范围外的文件。
4. 遵守仓库根目录 `AGENTS.md`：开发期间使用聚焦测试；全量回归、跨服务 E2E、负载、
   故障注入和 Docker 验证集中到统一验收检查点。
5. 禁止 big-bang 重写。所有阶段必须可独立部署、可观测、可回滚，并由服务端 feature
   flag、版本和 room epoch 控制切流。
6. 第一轮只完成代码级盘点与可执行重构计划，不修改业务代码。计划审查通过后，再按计划
   分阶段实现。
7. 不得把“代码已写”“测试看起来正常”当作完成。没有测试报告、Trace、Temporal History、
   SQL 一致性结果或其他规定证据的检查项只能标记为 `TODO`。

## 2. 必读权威资料

按以下优先级读取并引用，不得用记忆或归档文章替代：

1. `docs/architecture/temporal-first-agent-platform.md`
2. `docs/acceptance/temporal-first-agent-platform-verification-checklist.md`
3. `docs/acceptance/current-room-function-baseline.md`
4. `docs/contracts/hearing-flow-v2.md`
5. `docs/architecture/README.md`
6. `AGENTS.md`、`README.md`、`docs/api/README.md`、`docs/database/README.md`、
   `docs/deployment/README.md`、`docs/release/README.md`
7. 当前生产代码、数据库 migration 和自动化测试

`archive/legacy-docs` 只用于理解历史，不是需求或实现依据。发生冲突时：

- 当前功能事实以生产代码、合同和 `current-room-function-baseline.md` 为准；
- 重构终态以 `temporal-first-agent-platform.md` 为准；
- 发布判定以生产验证清单和功能基线的双重门禁为准。

## 3. 总目标

实现以下单一职责边界：

- **Temporal**：宏观阶段、房间阶段、顺序、等待、Timer、Update/Signal、取消、重试、
  补偿和失败恢复的唯一流程权威。
- **Java + PostgreSQL**：身份、权限、正式消息、证据、提交、不可变 Artifact、人工决定、
  执行动作和审计记录的唯一领域账本。
- **Python + LangGraph**：有边界的房间认知事务、checkpoint、中间验证状态、Router、
  `Send`、Reducer 和人工输入中断；不能推进宏观流程或写正式领域表。
- **LangChain Core / LCEL**：统一 `Prompt -> Message -> ChatModel -> Parser` 对象流和
  `prompt | model | parser` Runnable 执行协议，承接回调、流式、用量、追踪和模型适配。
- **前端**：只消费 Java 的权限过滤投影与正式/临时流事件，不推断正式状态，不成为
  正确性依赖。

最终系统必须支持目标架构规定的 1,000 个活跃房间、250 个并发 AgentRun、100 个持续
和 200 个突发模型调用，以及 2,500 个可重连 SSE 客户端。具体 SLO、容量假设和证据以
架构与验证清单为准，不得自行放宽。

## 4. 不可违反的架构约束

### 4.1 单一权威写入者

1. 同一阶段、Timer、外部等待或正式事实在任一时刻只能有一个 writer。
2. Java 与 Temporal 不得独立计算同一个 next stage。
3. Temporal 与 LangGraph 不得同时等待同一个外部参与方事件。
4. Redis、SSE、进程内缓存和 Elasticsearch 不能成为正式正确性的依赖。
5. Python 不得直接写 Java Domain DB，不得正式审批、退款、补发、关单或执行。
6. Agent 输出在 Java Finalizer 验收成功前始终是 proposal。

### 4.2 Temporal 确定性和载荷边界

1. Workflow 代码不得直接访问数据库、HTTP、模型、随机数或系统时钟。
2. I/O 和副作用放入幂等 Activity，并使用 heartbeat、超时、retry taxonomy 和 fencing。
3. Temporal History 只保存小型引用、hash、revision、版本和流程状态，不保存证据正文、
   完整矩阵、Prompt、模型全文或 token delta。
4. 使用稳定且不含 PII 的 Workflow ID、room epoch、Continue-As-New 和 Worker Versioning。
5. 正常低延迟入口使用 Update-With-Start；Java 事务 outbox 是 Temporal 不可用时的可靠
   恢复路径。

### 4.3 Java 领域账本边界

1. 命令受理、领域事实和 outbox 在明确的本地事务边界提交。
2. 所有命令绑定 tenant、case、room epoch、actor scope、command ID、版本、revision、
   deadline 和 canonical hash。
3. 相同命令和相同 hash 幂等返回；相同命令和不同 hash 必须冲突并记录安全审计。
4. Projection 写入必须校验 process revision、room epoch 和 fencing token，旧结果不能覆盖
   新状态。
5. 正式消息、阶段动作、Artifact、人工决定和执行记录保持 append-only 或版本化不可变。

### 4.4 LangGraph 和 LangChain 边界

1. 使用 PostgreSQL checkpointer、GraphRegistry、graph version pinning、command ledger、
   lease/fencing 和 thread scope 隔离。
2. Graph State 必须类型化；每个字段明确 owner、生命周期、大小上限、持久化策略和
   Reducer 语义。
3. Router 只做认知分支，不做宏观业务阶段推进。
4. 并行处理使用有界 `Send` 和 keyed reducer；Reducer 必须验证确定性、结合律、排序、
   去重和冲突行为。
5. 模型节点统一使用受治理 LCEL Runnable，不允许各 Agent 私建 HTTP 调用、重试、
   JSON 截取或错误协议。
6. 建立 `GovernedChatModel`/模型适配层，统一 Prompt、Message、ChatModel、Parser、回调、
   流式、token 使用、超时、重试预算和 Trace。
7. system 指令与不可信案件内容分离；Prompt、Model、Schema、Policy 和 Guardrail 均版本化
   并绑定 hash。

### 4.5 流式和恢复语义

1. 将当前 `agent_stream.v1` 升级为明确区分 logical run 与 attempt 的版本化协议。
2. provisional delta 不能直接成为正式消息；只有通过 Finalizer 的 committed final 才能落库。
3. 自动重试必须有 attempt/reset 语义，不能把多个 attempt 的文本拼接成一条回复。
4. SSE 支持 cursor replay、持久化 catch-up、慢消费者治理、权限过滤和刷新恢复。
5. 浏览器断开不能取消已经被系统正式接受且需要继续完成的领域工作；只取消无必要的
   网络投影和未提交预览。

## 5. 当前功能零回归要求

完整读取 `current-room-function-baseline.md`，并建立 99 个行为编号到实施任务和测试的
追踪关系。至少覆盖：

- `OVR-001..007`：总览、阶段导航、相对方接待门禁、发起、导入和删除；
- `SEC-001..006`：案件参与关系、精确 actor、audience、service secret 和 Agent 无执行权；
- `CORE-001..012`：持久化、sequence、SSE、AgentRun 恢复、流协议、历史模式和通知；
- `UI-001..005`：固定外壳、断点、44px 控件、焦点、Escape、长文本和大列表；
- `INT-001..010`：双方私有顺序接待、受理/不受理/取消、矩阵和卷宗；
- `EVD-001..015`：私有证据、上传、批次、核验、人工复核、完成、封卷和 2 小时时钟；
- `HRG-001..019`：固定 15 阶段、共享 deadline、当事方终态、补证、卷宗、V1-Jury-V2、
  handoff、恢复和隐藏和解主线；
- `DRF-001..006`：非最终草案、结构化内容、历史兼容和终审入口；
- `REV-001..012`：冻结 Packet、Copilot、审核员授权、五类决定和执行边界；
- `OUT-001..007`：正式结果门槛、真实 action record、模拟动画标识和 Tool Executor 边界。

对基线中的 `GAP-001..012`，计划必须逐项标注：

- `PRESERVE`：迁移期间保持现状；
- `FIX_WITH_DECISION`：作为明确产品/架构变更修复，并新增迁移和回归证据；
- `REMOVE_WITH_DECISION`：经批准删除兼容能力。

不得在框架重构中静默“顺便修复”或改变这些行为。

## 6. 必须产出的重构计划

第一轮在 `plans/temporal-langgraph-room-refactor.md` 生成计划，并停止在计划审查门。计划
必须基于实际代码调用链，不得只复述目标架构。至少包含以下章节。

### 6.1 现状到目标差距

按前端、Java API/Application、Domain DB、Temporal、AgentRun、Python Graph、LCEL、
SSE、对象存储和可观测性分别列出：

- 当前实现及关键文件；
- 目标实现；
- 缺失能力；
- 需要保留的兼容面；
- 数据和状态迁移风险；
- 对应架构 Check ID 和基线 ID。

必须特别核对：

- 当前只有举证窗口由 Temporal 管理；
- 接待/证据是无 checkpointer 的单轮图；
- `hearing_flow.py` 是七个独立操作，不是 LangGraph；
- Java 当前持有 `hearing_flow.v2` 的 15 阶段 cursor 和 Spring deadline scheduler；
- 当前 AgentRun 主要由 Java Worker/恢复调度器执行；
- `DRAFT`、`OUTCOME` 不是 `RoomType`；
- Java 正式账本和前端可见行为不能因迁移而丢失。

### 6.2 状态所有权矩阵

为每个状态字段列出：

```text
状态/对象
当前 writer
目标 writer
reader
持久化位置
revision/epoch/fencing
幂等键
迁移阶段
切流条件
回滚条件
```

至少覆盖 case phase、room phase、party wait、deadline、AgentRun、graph checkpoint、
正式消息、证据、矩阵、trial dossier、V1、jury report、V2、ReviewPacket、approval、
execution action、SSE cursor 和 query projection。

### 6.3 合同和数据模型

给出新增或演进的版本化合同：

- `CaseCommandRef`
- `RoomGraphCommand`
- `RoomGraphResult`
- logical AgentRun / attempt
- stream attempt/reset/final/error
- process projection / room epoch / revision
- graph thread / checkpoint / command ledger / lease
- Artifact reference 与 canonical hash

列明 Java/Pydantic/JSON Schema 的生成或合同测试方案、兼容 reader/writer 顺序、Flyway
migration、索引、唯一约束、数据回填和回滚策略。

### 6.4 目标模块和依赖方向

给出目标目录/类/模块图和依赖约束，明确：

- Java API 与 Temporal Worker 是否拆部署；
- Case Workflow、Room Child Workflow、Activity 和 projection writer 的边界；
- Python Graph Kernel、Registry、State Lens、Reducer、node library 和 LCEL runtime；
- intake、evidence、hearing、review/outcome graphs 的独立拓扑；
- Java/Python contract package；
- 禁止依赖及 ArchUnit/static check。

不得设计一个包含所有房间和长期等待的万能 LangGraph，也不得引入隐藏类型边界的动态
JSON workflow DSL。

### 6.5 分阶段任务分解

严格使用目标架构 Phase 0–8，并为每阶段提供：

```text
目标和非目标
进入条件
PR/提交级任务顺序
精确影响文件或模块
数据库/合同变化
feature flag 与版本策略
shadow/parity 方法
聚焦测试
故障注入
观测指标和告警
回滚步骤
退出门禁
架构 Check ID
功能基线 ID
验收证据路径
```

阶段要求：

1. **Phase 0 Decisions and contracts**：ADR、SLO、状态 writer、schema、版本、retry taxonomy，
   对应 `MIG-000`。
2. **Phase 1 Temporal foundation**：Case/Room Workflow、inbox/outbox、Update-With-Start、
   projection fencing、reconciliation、独立 task queue，对应 `MIG-001`。
3. **Phase 2 AgentRun V2**：logical run/attempt、Activity heartbeat、reset、Finalizer 幂等、
   polling scheduler 降级为检测器，对应 `MIG-002`。
4. **Phase 3 Python graph platform**：PostgreSQL checkpointer、command ledger、lease、
   GraphRegistry、State Lens、Reducer、受治理 LCEL，对应 `MIG-003`。
5. **Phase 4 Intake pilot**：私有 thread、shadow、memory 迁移、外部等待交给 Temporal，
   对应 `MIG-004` 和全部 `INT-*`。
6. **Phase 5 Evidence**：扩展证据 Child Workflow、有界文件 fan-out、keyed reducer、100 文件
   验收、Java 证据账本不变，对应 `MIG-005` 和全部 `EVD-*`。
7. **Phase 6 Hearing**：Temporal 唯一推进 `hearing_flow.v2`，Python 拆为接待官、书记官、
   法官、评审团子图，保持 15 阶段与 hash 链，对应 `MIG-006` 和全部 `HRG-*`。
8. **Phase 7 Review/outcome/execution**：reviewer wait、冻结 Artifact、审批、幂等执行和补偿，
   对应 `MIG-007`、`DRF-*`、`REV-*`、`OUT-*`。
9. **Phase 8 Cleanup/hardening**：关闭旧 writer/scheduler/memory 所有权，完成 load、chaos、
   replay、security、DR 和旧版本引用清理，对应 `MIG-008`。

每个任务控制在可审查范围内。跨 Java、Python、前端和数据库的合同变化必须先做 additive
reader，再切 writer，最后删除旧路径。

### 6.6 切流和回滚

计划必须给出：

- 新 room epoch canary；
- old/new shadow comparison，不产生第二个正式结果；
- dual reader、single writer；
- active Workflow/Graph version pinning；
- checkpoint 只在安全边界迁移；
- deadline 和 Signal 的竞态演练；
- Temporal 不可用、Provider 不可用、Java/Python 重启时的降级行为；
- 回滚后如何避免恢复旧 writer 导致双写；
- 清理旧 Worker/Graph/scheduler 前的活跃引用查询。

## 7. 实施阶段要求

计划获批后才进入实施，并遵循：

1. 每个阶段开始前更新任务清单和状态所有权矩阵。
2. 先写失败测试或合同测试，再实现最小闭环。
3. 每个阶段只运行聚焦静态检查、单元测试、Temporal time-skipping/replay、合同测试和必要
   集成测试。
4. 每阶段完成后提供：代码摘要、migration、测试结果、未决风险、回滚命令、Check ID 和
   baseline ID 覆盖情况。
5. P0 门禁不通过不得进入下一阶段；P1/P2 例外必须按验证清单记录责任人、截止时间和
   风险接受，不能口头跳过。
6. 禁止为了让测试通过而降低权限、删除幂等校验、吞掉异常、伪造模型终态或绕过
   Finalizer。
7. 不保留两个自动调度器消费同一 AgentRun，也不保留 Java 和 Temporal 两套阶段推进。

## 8. 双重验收协议

### 8.1 目标架构验收

完成 `temporal-first-agent-platform-verification-checklist.md` 的完整证据表：

- P0 必须 100% 通过，不允许豁免；
- P1 原则上全部通过，例外必须有规定签字；
- P2 可延期但必须有负责人和截止日期；
- `N/A` 必须写原因和未来启用条件；
- `MIG-000..008` 按阶段顺序通过；
- 最终候选版本执行 `GATE-001..010`，证据来自同一个 commit 和同一套部署。

证据统一写入：

```text
test-reports/temporal-first/{release-id}/
```

### 8.2 当前功能基线回归

建立并完成 99 行基线追踪表：

```text
Baseline ID
现有行为
新实现路径
自动化测试
手工/E2E场景
结果
证据路径
行为变更审批（如有）
```

不得只按房间抽样。`OVR-*`、`SEC-*`、`CORE-*`、`UI-*`、`INT-*`、`EVD-*`、`HRG-*`、
`DRF-*`、`REV-*`、`OUT-*` 必须全部有结论。

### 8.3 统一生产检查点

遵守 `AGENTS.md`，只在所有实施阶段完成后进行一次统一重型验证，至少包括：

1. Java、Python、前端、Temporal 的完整测试与构建；
2. Docker 全服务部署；
3. USER、MERCHANT、PLATFORM_REVIEWER 多角色全链路 E2E；
4. 1,000 room / 250 AgentRun / 2,500 SSE 综合负载；
5. 100 sustained / 200 burst 模型并发；
6. 重复、乱序、延迟、hash 冲突命令；
7. Java、Python、Temporal Worker、Redis、LiteLLM、PostgreSQL 故障注入；
8. Temporal replay、Worker Versioning、Graph version pinning、checkpoint 恢复和回滚；
9. 跨 actor、跨角色、跨 tenant 泄露扫描；
10. PITR/DR、执行幂等和补偿演练。

任何重复正式消息/Artifact、已接受命令丢失、旧 revision 覆盖、私有数据泄露、不可恢复
checkpoint、未经人工批准的执行或 SLO 超标都直接判定失败。

## 9. 计划输出格式

第一轮回复必须按以下顺序输出，并同步写入计划文件：

1. **执行摘要**：当前状态、目标、范围、非目标和关键假设。
2. **发现的问题**：文档与代码冲突、缺失决策、风险，按严重度排序。
3. **现状调用链**：前端 -> Java -> Temporal/AgentRun -> Python -> Finalizer -> SSE。
4. **目标架构图**：使用 Mermaid，标明 process truth、domain truth、cognitive truth。
5. **状态所有权矩阵**。
6. **合同和数据迁移表**。
7. **Phase 0–8 依赖图与详细任务**。
8. **每阶段 feature flag、shadow、cutover、rollback 和退出门禁**。
9. **架构 Check ID 追踪矩阵**。
10. **99 项基线 ID 追踪矩阵**。
11. **测试金字塔与统一生产检查点**。
12. **容量、SLO、观测和运行手册计划**。
13. **风险登记表**：概率、影响、检测、缓解、owner、触发回滚阈值。
14. **需要决策的问题**：只保留无法从代码和权威文档确定的事项。
15. **推荐第一批 PR**：精确到文件、测试和退出标准。

禁止输出只有原则、没有文件和测试的“架构愿景”；禁止用“后续补充”代替 P0 设计；
禁止在未读取代码的情况下估算完成度。

## 10. 完成定义

只有同时满足以下条件才可以声明重构完成：

1. Phase 0–8 的退出门禁全部满足。
2. Temporal、Java、LangGraph、LangChain 和前端不存在重叠权威 writer。
3. 所有正式运行均可追溯到 Workflow、Graph、Prompt、Model、Schema、Policy、Guardrail、
   input hash 和 output hash。
4. 验证清单 P0 全部通过，P1/P2 按规则处置。
5. 99 个当前功能基线 ID 全部通过或有正式行为变更审批。
6. `GATE-001..010` 在同一候选 commit 上通过。
7. 没有活跃 Workflow、Graph thread 或 room epoch 依赖被删除的旧实现。
8. 回滚、故障恢复、值班 Runbook、Dashboard 和 DR 演练均有证据。
9. 工作区无意外改动，最终提交可构建、可部署、可回放、可审计。

若任一条件缺少证据，明确报告“未完成”及阻断项，不得给出虚假完成结论。
