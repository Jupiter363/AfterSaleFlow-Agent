# Temporal-first Agent Platform 生产验证清单

- 版本：v1
- 更新：2026-09-04
- 状态：当前生产发布门禁
- 对应架构：[`temporal-first-agent-platform.md`](../architecture/temporal-first-agent-platform.md)
- 可重复回归输入：[`canonical-full-chain-uat-fixture.md`](./canonical-full-chain-uat-fixture.md)
- 当前浏览器证据：[`current-uat-baseline.md`](../release/current-uat-baseline.md)

## 1. 使用规则

本清单用于架构实现、阶段切换和生产发布验收。勾选表示已经取得可复核证据，
不表示“代码已经写完”或“人工看起来正常”。
发布同时要求当前业务合同和全链路 UAT 基线通过，除非产品已批准并记录行为变更。

### 1.1 优先级

- **P0**：数据一致性、安全、权限、幂等、恢复和正式结果正确性。任何失败都阻断发布。
- **P1**：容量、SLO、可观测性、运维和版本升级。生产发布前原则上必须全部通过。
- **P2**：成本、体验和长期优化。允许带明确负责人、截止时间和风险接受记录上线。

### 1.2 证据要求

每个勾选项必须在发布证据表中填写至少一种证据：

```text
自动化测试报告
Temporal 测试或真实 History
SQL 约束或一致性查询结果
OpenTelemetry Trace
Prometheus/Grafana 指标快照
故障注入记录
负载测试原始报告
安全测试报告
恢复演练记录
代码/配置静态审计链接
```

本地生成的证据统一存放在下列临时目录；该目录被 Git 忽略，正式发布记录应由 CI
Artifact 或外部受控证据库保存：

```text
test-reports/temporal-first/{release-id}/
```

### 1.3 发布门禁

- P0：必须 100% 通过，不允许豁免。
- P1：必须通过；例外需架构、SRE、安全三方签字，并且不得影响正式数据正确性。
- P2：允许延期，但必须进入问题跟踪系统。
- `N/A` 必须写明不适用的阶段、原因和未来启用条件。
- 任何跨租户泄露、重复正式结果、已接受命令丢失、旧版本覆盖新状态均直接判定失败。

## 2. 验证环境和基准负载

### 2.1 环境一致性

- [ ] `ENV-001` **P0** 验证环境启用与生产一致的 Temporal、PostgreSQL、Redis、对象存储、Java Worker 和 Python 多副本拓扑。
- [ ] `ENV-002` **P0** Temporal Workflow/Activity、Graph、Prompt、Schema、Policy 和模型配置版本与待发布版本一致。
- [ ] `ENV-003` **P1** Java API、Temporal Worker、Python Agent、LiteLLM 均至少运行两个副本；最终 HA 验收使用三个可用区或等价故障域。
- [ ] `ENV-004` **P0** Domain DB、Graph DB、Temporal persistence 使用独立数据库或 schema、账户、连接池和迁移权限。
- [ ] `ENV-005` **P0** 测试使用脱敏或合成数据，禁止把生产 PII 复制到普通压测环境。
- [ ] `ENV-006` **P1** 所有节点启用统一时钟同步、trace context 和结构化日志。
- [ ] `ENV-007` **P1** 模型负载测试使用可控制延迟、错误、截断和流式行为的 Provider Stub；另执行真实 Provider 合同抽样。
- [ ] `ENV-008` **P1** Java API/Worker、Python、LiteLLM和OTel的最小副本、初始资源、HPA信号、PDB及拓扑分散符合架构§14.1，任何偏差有负载证据。
- [ ] `ENV-009` **P1** PgBouncer保护连接突发，报表/大列表查询走只读副本，不与控制面关键事务争抢主库连接池。

### 2.2 基准工作负载

- [ ] `ENV-010` **P1** 创建并保持 1,000 个同时活跃的房间 Workflow，其中至少 70% 处于等待或 Timer 状态。
- [ ] `ENV-011` **P1** 建立 2,500 个 SSE 连接，覆盖用户、商家和审核员可见范围。
- [ ] `ENV-012` **P1** 稳态输入达到 20 条房间命令/秒，其中 5 条/秒触发 Agent。
- [ ] `ENV-013` **P1** AgentRun 突发并发达到 250，持续模型调用并发达到 100，短时突发达到 200。
- [ ] `ENV-014` **P1** 单个证据批次包含 100 个文件，单房间实际并发评估不超过 8。
- [ ] `ENV-015` **P1** 记录模型平均服务时间并用 `并发 = 到达率 × 服务时间` 校验容量假设；不允许只报告线程数。
- [ ] `ENV-016` **P1** Provider 请求和 Token 配额在稳态下保持至少 30% 余量。
- [ ] `ENV-017` **P1** 输入突发达到50条房间命令/秒、20条Agent触发命令/秒并持续30秒，随后队列有界回落。

## 3. 权威状态与架构边界

- [ ] `ARCH-001` **P0** Temporal 是宏观阶段、房间阶段、等待、Timer、取消、重试和补偿的唯一权威写入者。
- [ ] `ARCH-002` **P0** Java API/Application Service 不存在绕过 Temporal 直接推进宏观阶段的生产入口。
- [ ] `ARCH-003` **P0** Java PostgreSQL 是正式消息、证据、提交、Artifact、裁决、执行和审计记录的唯一权威账本。
- [ ] `ARCH-004` **P0** Python 生产账户没有 Domain DB 写权限，也不能直接创建正式消息、证据或裁决。
- [ ] `ARCH-005` **P0** LangGraph 只持有认知状态，不能推进 Java/Temporal 宏观流程。
- [ ] `ARCH-006` **P0** 同一个外部等待条件不存在 Temporal Timer/Signal 与 LangGraph `interrupt` 双重所有权。
- [ ] `ARCH-007` **P0** Redis、Elasticsearch、SSE 连接和进程内缓存均不是正式正确性的依赖。
- [ ] `ARCH-008` **P0** Agent 输出在 Java Finalizer 成功前始终是 Proposal，不能成为正式领域对象。
- [ ] `ARCH-009` **P0** Tool Executor 只能执行已批准、哈希绑定、幂等的动作快照，Agent不能直接退款、补发或关单。
- [ ] `ARCH-010` **P1** 静态依赖测试或 ArchUnit/脚本能够阻止 Workflow 代码依赖 Repository、HTTP、模型客户端、随机数或系统时钟。
- [ ] `ARCH-011` **P1** 架构 README、模块图、运行手册和实际依赖方向保持一致，不存在旧的“双状态机”描述。

## 4. 跨服务合同

- [ ] `CONTRACT-001` **P0** `CaseCommandRef`、`RoomGraphCommand`、`RoomGraphResult` 均有独立 `schema_version`。
- [ ] `CONTRACT-002` **P0** JSON Schema 是权威合同，Java和Pydantic类型通过生成或合同测试保持一致。
- [ ] `CONTRACT-003` **P0** 未知必需版本、缺失字段、额外危险字段、非法枚举和超限输入均失败关闭。
- [ ] `CONTRACT-004` **P0** 所有命令绑定 tenant、case、room epoch、actor scope、command ID、版本和截止时间。
- [ ] `CONTRACT-005` **P0** 同一个 `command_id` 与相同 RFC 8785 canonical hash 重放返回已有状态。
- [ ] `CONTRACT-006` **P0** 同一个 `command_id` 与不同 hash 返回冲突并产生安全审计事件。
- [ ] `CONTRACT-007` **P0** Snapshot、Event、Graph Result 和正式 Artifact 的 SHA-256 绑定在接收端重新计算验证。
- [ ] `CONTRACT-008` **P0** Temporal History 只保存小型引用、hash、版本和流程数据，不保存证据正文、完整矩阵和大模型流。
- [ ] `CONTRACT-009` **P1** 合同兼容矩阵覆盖当前版本、前一版本、非法未来版本和字段增删场景。
- [ ] `CONTRACT-010` **P1** Java/Python 合同测试在 CI 中运行，并能在一侧单独升级时发现不兼容变更。
- [ ] `CONTRACT-011` **P0** 超大 payload 通过已授权的 payload/snapshot reference 传递，不以内联方式绕过尺寸限制。
- [ ] `CONTRACT-012` **P0** Trace、Actor、Prompt Profile、Model Profile 和 Tool Capability 不能由浏览器自由覆盖。
- [ ] `CONTRACT-013` **P0** Graph终态只允许`COMPLETED`、`NEEDS_INPUT`、`NEEDS_REVIEW`、`FAILED`，每种状态的必需引用和错误字段均通过正反合同测试。

## 5. Temporal Workflow

### 5.1 Workflow 身份与状态

- [ ] `TEMP-001` **P0** 每个案件使用稳定且不含 PII 的 `CaseProcessWorkflow` ID。
- [ ] `TEMP-002` **P0** 每个房间重开产生新的 `room_epoch` 和 Child Workflow，不复用已关闭实例。
- [ ] `TEMP-003` **P0** Case Workflow 只保存小型流程状态、revision、room epoch、Child ID 和有界命令队列。
- [ ] `TEMP-004` **P0** Search Attributes 不包含姓名、消息、证据正文、外部账号等 PII。
- [ ] `TEMP-005` **P1** Java查询接口读取版本化DB投影，不用 Temporal Query 扫描案件列表。

### 5.2 Update、Signal 与顺序

- [ ] `TEMP-010` **P0** 正常路径使用 Update-With-Start，`command_id` 作为 Update ID。
- [ ] `TEMP-011` **P0** Java事务提交后、Temporal调用前杀死API进程，outbox仍能最终投递命令。
- [ ] `TEMP-012` **P0** Temporal不可用时，已落库命令返回 `PENDING_ORCHESTRATION`，恢复后不丢失、不重复执行。
- [ ] `TEMP-013` **P0** Update/Signal Handler只入队，Workflow主循环串行执行领域决策。
- [ ] `TEMP-014` **P0** 同一案件并发命令获得严格递增的 `case_command_sequence`。
- [ ] `TEMP-015` **P0** 跨 Continue-As-New 重发同一命令，由Java命令账本去重，不依赖单Run Update缓存。
- [ ] `TEMP-016` **P0** 异步事件携带 `case_event_sequence`，重复事件被忽略。
- [ ] `TEMP-017` **P0** 乱序事件不会推进状态；缺口通过有界缓冲或 `LoadDomainEventsActivity` 补齐。
- [ ] `TEMP-018` **P0** 一个缺失事件不会导致无界内存缓冲或永久静默等待，存在告警和人工恢复路径。

### 5.3 Timer、取消与恢复

- [ ] `TEMP-020` **P0** 使用 time-skipping 测试验证提醒、截止、双方提前完成和超时封卷。
- [ ] `TEMP-021` **P0** 重复party-completed Signal不会重复推进或重置共享截止时间。
- [ ] `TEMP-022` **P0** Workflow取消传播到Activity、Java内部流和Python任务，迟到结果被room epoch/fencing拒绝。
- [ ] `TEMP-023` **P0** Worker在Timer等待、Activity执行和阶段转换时分别被杀死，Workflow均能恢复。
- [ ] `TEMP-024` **P0** Activity commit成功但completion响应丢失时，重试从`domain_operation`账本返回原结果。
- [ ] `TEMP-025` **P1** Workflow在房间切换、24小时或2,000个History事件阈值达到时正确Continue-As-New；阈值调整有容量和replay证据。
- [ ] `TEMP-026` **P1** Continue-As-New前后Search Attributes、revision、pending wait和Child结果连续。
- [ ] `TEMP-027` **P1** 捕获History执行replay测试，新Worker版本无不确定性异常。
- [ ] `TEMP-028` **P1** Worker Versioning确保旧Workflow仍由兼容Build处理，新Workflow进入新Build。
- [ ] `TEMP-029` **P1** History事件数、大小和replay耗时有监控与告警阈值。

### 5.4 Activity策略

- [ ] `TEMP-030` **P0** 每个side-effect Activity有稳定operation key和幂等数据库记录。
- [ ] `TEMP-031` **P0** 业务冲突、权限、stale revision、合同和guardrail错误标为non-retryable。
- [ ] `TEMP-032` **P0** 基础设施Activity最大尝试次数、backoff、heartbeat和绝对deadline有统一策略。
- [ ] `TEMP-033` **P0** 重试预算从Workflow传递到Activity、Python和模型层，不能指数相乘。
- [ ] `TEMP-034` **P1** `case-control`、`room-control`、`agent-execution`、`notification-and-tools`使用独立Task Queue。
- [ ] `TEMP-035` **P1** 模型拥塞不会提高Timer、取消或审核Task Queue的调度延迟。
- [ ] `TEMP-036` **P0** Agent Activity基线策略为10分钟StartToClose、15秒HeartbeatTimeout、基础设施失败最多3次；偏差必须显式配置并受命令deadline约束。

## 6. Java领域账本与事务

- [ ] `JAVA-001` **P0** Command Inbox与Outbox在同一ACID事务内写入。
- [ ] `JAVA-002` **P0** `(case_id, command_id)`或全局命令键有唯一约束和request hash。
- [ ] `JAVA-003` **P0** `case_command_sequence`在案件级锁或等价串行化机制下分配。
- [ ] `JAVA-004` **P0** 正式消息、Hearing Action、Artifact和审核记录保持append-only约束。
- [ ] `JAVA-005` **P0** Process Projection更新包含`process_revision < new_revision` fencing条件。
- [ ] `JAVA-006` **P0** 旧Activity和旧Workflow Run不能覆盖新projection。
- [ ] `JAVA-007` **P0** Finalizer同时验证case、room epoch、stage sequence、actor scope、AgentRun、schema和hash。
- [ ] `JAVA-008` **P0** Finalizer重复调用返回同一正式对象，不创建第二条消息或Artifact。
- [ ] `JAVA-009` **P0** Java在同一事务提交正式结果、AgentRun终态、审计事件和必要outbox。
- [ ] `JAVA-010` **P0** Agent失败、部分流和未校验JSON不能进入正式房间消息表。
- [ ] `JAVA-011` **P0** 权限检查绑定真实参与关系、房间、角色、scope和audience，而非仅相信请求字段。
- [ ] `JAVA-012` **P0** Domain DB失败时系统失败关闭，不通过缓存或Python结果绕过正式事务。
- [ ] `JAVA-013` **P1** Projection drift扫描能够检测并修复Temporal revision与Java投影不一致。
- [ ] `JAVA-014` **P1** 查询接口在Projection延迟时返回明确版本/处理中状态，而非猜测下一阶段。
- [ ] `JAVA-015` **P1** Domain Operation、Command、Outbox和Finalizer均有清理、归档和索引计划。

## 7. AgentRun与流式执行

### 7.1 逻辑Run和Attempt

- [ ] `RUN-001` **P0** Logical AgentRun与Attempt分离，一个逻辑Run只能有一个正式final结果。
- [ ] `RUN-002` **P0** `(case_id, logical_idempotency_key)`和`(run_id, attempt_no)`有唯一约束。
- [ ] `RUN-003` **P0** Attempt记录provider、model、graph/checkpoint、usage、latency、error和visible-output状态。
- [ ] `RUN-004` **P0** Temporal Activity重复执行时复用logical run，不新建第二个正式运行。
- [ ] `RUN-005` **P0** Python已完成但Java未收到结果时，重试通过command ledger返回缓存final，不重新调用模型。
- [ ] `RUN-006` **P0** Java最终落库失败时，Temporal只重试Finalizer，不重新运行模型。
- [ ] `RUN-007` **P1** Agent Activity至少每5秒heartbeat，15秒HeartbeatTimeout故障可恢复。
- [ ] `RUN-008` **P0** Temporal接管后，legacy polling scheduler不再自动执行同一AgentRun队列。
- [ ] `RUN-009` **P0** `(agent_run_attempt_id, sequence_no)`有唯一约束，重复流事件写入不会产生第二个可见chunk。

### 7.2 流式协议

- [ ] `STREAM-001` **P0** 协议覆盖`attempt_started`、`visible_delta`、`usage`、`attempt_aborted`、`attempt_reset`、`final`和`error`。
- [ ] `STREAM-002` **P0** Partial visible output失败后重试会生成新attempt并发送`attempt_reset`。
- [ ] `STREAM-003` **P0** 前端收到reset后删除旧attempt临时文本，不把两个attempt拼接。
- [ ] `STREAM-004` **P0** 只有`final`事件可以触发正式消息/Artifact Finalizer。
- [ ] `STREAM-005` **P0** 非白名单字段、raw JSON、reasoning和内部错误栈不能进入公开流。
- [ ] `STREAM-006` **P0** 客户端使用`run_id + attempt_id + sequence_no`断线续传，不丢失或重复展示。
- [ ] `STREAM-007` **P1** Delta按字段在50-100ms或1-4KiB范围内合并，避免逐Token事务。
- [ ] `STREAM-008` **P1** 100持续调用下durable stream写入约束在设计预算内，并使用批量插入。
- [ ] `STREAM-009` **P1** SSE慢消费者有有界缓冲，超限后断开并允许重放，不拖垮Java堆。
- [ ] `STREAM-010` **P1** Redis发布丢失时，SSE节点通过durable high-watermark发现并补发缺口。
- [ ] `STREAM-011` **P1** Redis完全故障期间，正式结果正确且客户端可从PostgreSQL恢复。
- [ ] `STREAM-012` **P1** Stream表按时间分区，完成后压缩，hot chunk至少保留24小时后安全清理。
- [ ] `STREAM-013` **P0** Partition删除前验证压缩/归档成功，保留terminal event和AgentRun manifest。

## 8. Python LangGraph运行时

### 8.1 Checkpoint、命令与隔离

- [ ] `GRAPH-001` **P0** 生产Graph使用PostgreSQL checkpointer，不使用MemorySaver作为恢复依据。
- [ ] `GRAPH-002` **P0** Graph启动时完成schema setup/migration，失败时服务不进入ready状态。
- [ ] `GRAPH-003` **P0** `(thread_id, command_id)`在Graph Command Ledger中唯一。
- [ ] `GRAPH-004` **P0** 相同command/hash返回同一checkpoint/result；不同hash失败关闭。
- [ ] `GRAPH-005` **P0** Graph lease使用持久化fencing token，旧执行者不能提交新状态。
- [ ] `GRAPH-006` **P0** 在model前、model后checkpoint前、checkpoint后response前分别杀死Python进程，恢复结果符合定义。
- [ ] `GRAPH-007` **P0** room epoch变化后，旧Graph线程和迟到结果不能写入当前房间。
- [ ] `GRAPH-008` **P0** Intake/Evidence按tenant、case、actor、room epoch、agent session隔离thread。
- [ ] `GRAPH-009` **P0** Shared Hearing Graph只接收正式可见Artifact，不接收双方私聊原文。
- [ ] `GRAPH-010` **P0** Python实例无本地粘性状态需求，任意副本都能恢复同一thread。

### 8.2 State、Router与Reducer

- [ ] `GRAPH-011` **P0** State只包含有界、可序列化数据，不包含客户端、连接池、密钥或工具实例。
- [ ] `GRAPH-012` **P1** Message窗口、memory summary、pending work和artifact引用均有尺寸上限与监控。
- [ ] `GRAPH-013` **P0** 每个模型节点使用State Lens，只选择必要字段，禁止把整个State自动放入Prompt。
- [ ] `GRAPH-014` **P0** Room topology使用显式`StateGraph`代码，可生成可读图，不依赖动态JSON DSL。
- [ ] `GRAPH-015` **P0** Conditional Edge/Router覆盖合法分支，未知route失败关闭。
- [ ] `GRAPH-016` **P0** `Send`只用于独立任务，并有单房间8、全局/tenant级并发上限。
- [ ] `GRAPH-017` **P0** 并行结果使用stable key合并；重复key冲突不是last-write-wins。
- [ ] `GRAPH-018` **P0** Reducer有结合律、确定性、重复输入、不同完成顺序和冲突属性测试。
- [ ] `GRAPH-019` **P0** 相同已验证输入和节点结果在不同并行完成顺序下得到相同正式state patch/hash。
- [ ] `GRAPH-020` **P0** Formal party wait和deadline以`NEEDS_INPUT`结果交给Temporal，不在Graph中长期interrupt。
- [ ] `GRAPH-021` **P1** Graph版本固定在room epoch，部署新版本不会隐式迁移活跃thread。
- [ ] `GRAPH-022` **P1** 旧Graph版本在无活跃thread前保持可加载，或有经过验证的显式迁移。

## 9. LangChain/LCEL模型协议

- [ ] `LCEL-001` **P0** 节点真实执行`State Lens -> Prompt -> ChatModel -> Parser -> Guardrail` Runnable链，不只是包装旧函数。
- [ ] `LCEL-002` **P0** Prompt输入类型、ChatPromptValue、System/Human Message和输出Schema均可单独测试。
- [ ] `LCEL-003` **P0** GovernedChatModel支持`invoke/ainvoke/batch/stream`和RunnableConfig callbacks。
- [ ] `LCEL-004` **P0** 非可信案件/证据文本只进入Human Message，不能覆盖System指令。
- [ ] `LCEL-005` **P0** Agent身份、Prompt Profile和能力列表只来自Java签发的可信上下文白名单。
- [ ] `LCEL-006` **P0** Provider strict JSON Schema、Pydantic校验和业务Guardrail依次生效。
- [ ] `LCEL-007` **P0** Provider拒绝response format时，只执行明确允许、有限次数的兼容回退。
- [ ] `LCEL-008` **P0** 隐藏reasoning/chain-of-thought不读取、不持久化、不进入stream或最终结果。
- [ ] `LCEL-009` **P0** 多模态输入只来自授权AssetLoader，manifest与evidence hash绑定。
- [ ] `LCEL-010` **P0** 浏览器或Graph State不能覆盖model、temperature、token budget、response format和tool allowlist。
- [ ] `LCEL-011` **P1** async模型链在100持续并发下无线程池耗尽、事件循环阻塞或连接泄漏。
- [ ] `LCEL-012` **P0** 本地provider retry最多两次、schema repair最多一次，并受总deadline/预算约束。
- [ ] `LCEL-013` **P1** 每次调用记录prompt hash、model profile、provider/model、tokens、latency、schema和policy版本。
- [ ] `LCEL-014` **P0** Prompt injection、跨方引用、伪造证据视觉状态和越权tool调用有失败测试。

## 10. 端到端一致性与幂等

- [ ] `E2E-001` **P0** 同一HTTP命令重复100次，只生成一个case command和一个正式业务结果。
- [ ] `E2E-002` **P0** 同一命令在Java提交后、Temporal接收前断网，恢复后恰好产生一个逻辑结果。
- [ ] `E2E-003` **P0** Temporal Activity调用Java提交成功后丢失响应，retry读取operation ledger。
- [ ] `E2E-004` **P0** Python返回final后连接断开，retry从Graph command ledger返回相同result hash。
- [ ] `E2E-005` **P0** Java Finalizer提交成功后Temporal未收到completion，retry不产生重复Artifact。
- [ ] `E2E-006` **P0** 旧process revision、stage sequence、room epoch和snapshot hash命令全部被拒绝。
- [ ] `E2E-007` **P0** Outbox多dispatcher乱序投递不会改变最终流程结果。
- [ ] `E2E-008` **P0** Case command、domain event、AgentRun、Graph command和formal artifact可通过ID/hash完整追踪。
- [ ] `E2E-009` **P0** 任意正式Artifact能证明唯一父Artifact、AgentRun、输入snapshot和生成版本。
- [ ] `E2E-010` **P0** Reconciliation在模拟projection缺失、旧revision和孤立pending command时恢复正确状态。
- [ ] `E2E-011` **P0** 系统不使用XA/2PC，所有跨服务失败点都有幂等重试或补偿测试。

## 11. 房间功能验证

### 11.1 接待室

- [ ] `ROOM-INTAKE-001` **P0** 用户和商家私有会话、memory、Prompt和stream audience完全隔离。
- [ ] `ROOM-INTAKE-002` **P0** 重复消息不会生成重复Agent回复或卷宗版本。
- [ ] `ROOM-INTAKE-003` **P0** Intake Graph只输出卷宗patch/建议，Temporal决定外部等待，Java Finalizer生成正式卷宗。
- [ ] `ROOM-INTAKE-004` **P0** `READY_TO_CONFIRM`不能绕过Java正式准入条件进入证据室。

### 11.2 证据室

- [ ] `ROOM-EVIDENCE-001` **P0** Evidence Graph只能读取当前actor有权访问的证据和正式接待卷宗。
- [ ] `ROOM-EVIDENCE-002` **P0** 100文件批次在8并发上限下全部达到terminal assessment后才执行一次merge。
- [ ] `ROOM-EVIDENCE-003` **P0** Reducer不同完成顺序产生相同matrix和hash。
- [ ] `ROOM-EVIDENCE-004` **P0** 未授权、hash不匹配、未实际加载的视觉证据不能被声明为已检查。
- [ ] `ROOM-EVIDENCE-005` **P0** 双方完成和共享截止Timer不会因首次提交被重置。
- [ ] `ROOM-EVIDENCE-006` **P0** 没有满足正式证据准入条件时，Temporal不能进入Hearing。

### 11.3 庭审室

- [ ] `ROOM-HEARING-001` **P0** `hearing_flow.v2`阶段顺序由Temporal Child Workflow唯一维护。
- [ ] `ROOM-HEARING-002` **P0** 两个party wait阶段均支持双方完成、单方缺席和共享deadline超时。
- [ ] `ROOM-HEARING-003` **P0** 问题集、回答、补证请求、证据批次、矩阵、dossier、V1、jury和V2的ID/hash链完整。
- [ ] `ROOM-HEARING-004` **P0** Judge V1、Jury Review、Judge V2使用独立Graph/Agent节点且输入版本固定。
- [ ] `ROOM-HEARING-005` **P0** Jury结果必须绑定V1 ID/hash，V2必须绑定V1和Jury ID/hash。
- [ ] `ROOM-HEARING-006` **P0** V2未完成或hash不匹配时不能打开人工审核。
- [ ] `ROOM-HEARING-007` **P0** 旧Java `nextStage`入口在切换后不可独立推进庭审。

### 11.4 Outcome、审核和执行

- [ ] `ROOM-OUTCOME-001` **P0** Agent草案始终是非最终建议，只有授权审核员能确认或修改。
- [ ] `ROOM-OUTCOME-002` **P0** 审核决定与展示给审核员的精确Artifact/hash绑定。
- [ ] `ROOM-OUTCOME-003` **P0** Temporal等待审核SLA，超时升级而不是自动批准高风险动作。
- [ ] `ROOM-OUTCOME-004` **P0** Tool Executor重复执行同一approved action key只产生一次外部效果。
- [ ] `ROOM-OUTCOME-005` **P0** Evaluation Agent只读取closed case，不反向修改流程、裁决、规则和Prompt。

## 12. 性能、容量和背压

- [ ] `PERF-001` **P1** 1,000活跃Workflow运行60分钟，Temporal task queue p95小于1秒。
- [ ] `PERF-002` **P1** Java durable command acceptance p95小于300ms。
- [ ] `PERF-003` **P1** SSE断线重放p95小于2秒。
- [ ] `PERF-004` **P1** 100持续模型并发下无Provider配额溢出，吞吐与Little's Law预测一致。
- [ ] `PERF-005` **P1** 200模型并发突发30秒后，队列能回落且没有retry storm。
- [ ] `PERF-006` **P1** 250 AgentRun突发时Timer/cancel/review Task Queue延迟不受影响。
- [ ] `PERF-007` **P1** 2,500 SSE连接下Java堆、线程/虚拟线程、文件描述符和连接池保持预算内。
- [ ] `PERF-008` **P1** Python四副本下Graph执行均衡，无单thread粘性和单pod热点。
- [ ] `PERF-009` **P1** Graph checkpoint p95、state大小和lease争用满足设定阈值并有指标。
- [ ] `PERF-010` **P1** Domain、Graph、Temporal数据库连接池在峰值下低于80%使用率。
- [ ] `PERF-011` **P1** Stream批量写入、分区索引和24小时hot retention经过容量估算与实测。
- [ ] `PERF-012` **P1** 队列超过SLO时停止非关键Agent，已接受命令不丢失，关键控制Task Queue继续服务。
- [ ] `PERF-013` **P1** Tenant bulkhead验证单一租户无法耗尽全部模型和Graph容量。
- [ ] `PERF-014` **P2** Token、Prompt上下文、重复证据评估和Provider成本达到预算目标。
- [ ] `PERF-015` **P1** 全局model/profile、tenant、room、node fan-out四层并发限制均生效，超额工作进入可观测有界队列。
- [ ] `PERF-016` **P1** Java command/query和Temporal control-plane的月度可用性SLI定义支持99.95%目标，并验证多窗口burn-rate告警。
- [ ] `PERF-017` **P1** Agent execution月度可用性SLI支持99.9%目标；Provider全局故障排除规则有审批且不能掩盖平台故障。
- [ ] `PERF-018` **P1** Model first-token p95按Provider/Model Profile单独统计，平台排队、首Token和完成延迟不能聚合成一个指标。
- [ ] `PERF-019` **P2** 对10倍活跃Workflow增长目标完成容量模型和分阶段无模型负载验证，明确下一扩容触发点。

## 13. 高可用和故障注入

- [ ] `HA-001` **P0** 随机终止一个Java API pod，已连接客户端可重连，已接受命令不丢失。
- [ ] `HA-002` **P0** 随机终止一个Java Temporal Worker，Activity由兼容Worker恢复。
- [ ] `HA-003` **P0** 随机终止一个Python pod，Graph从checkpoint/ledger恢复。
- [ ] `HA-004` **P0** 在模型调用完成但checkpoint前终止Python，Attempt状态和reset语义正确。
- [ ] `HA-005` **P0** 在checkpoint后、Java接收final前终止Python，不重复模型调用。
- [ ] `HA-006` **P0** 暂停Temporal可用性5分钟，命令进入outbox，恢复后顺序执行。
- [ ] `HA-007` **P0** Redis主节点故障或全集群不可用，正式流程正确且SSE可重放。
- [ ] `HA-008` **P0** LiteLLM单pod和多数pod故障触发重试/熔断，不产生伪成功。
- [ ] `HA-009` **P0** Provider超时、429、500、截断流和非法JSON分别符合retry taxonomy。
- [ ] `HA-010` **P0** Domain PostgreSQL主备切换不产生重复command、Artifact或丢失已提交事实。
- [ ] `HA-011` **P0** Graph PostgreSQL主备切换后checkpoint可恢复，旧lease不能提交。
- [ ] `HA-012` **P1** 网络分区Java-Python、Worker-Temporal、Java-Redis分别有明确降级和恢复行为。
- [ ] `HA-013` **P1** 24小时soak期间无持续内存增长、连接泄漏、History失控或队列漂移。
- [ ] `HA-014` **P1** 多AZ故障演练达到5分钟内恢复目标。
- [ ] `HA-015` **P1** 故障解除后系统自动收敛，不需要直接修改Temporal或数据库内部表。
- [ ] `HA-016` **P0** 单区域Temporal故障转移不丢失任何已确认Update、Signal或Timer状态，验证RPO为0个acknowledged event。

## 14. 安全和隐私

- [ ] `SEC-001` **P0** 外部命令在Java完成认证、案件参与关系和房间权限检查。
- [ ] `SEC-002` **P0** Java-Python使用mTLS和短期签名envelope，验证签名、expiry和nonce/replay。
- [ ] `SEC-003` **P0** 伪造tenant、case、room epoch、actor scope、thread ID和capability全部失败。
- [ ] `SEC-004` **P0** USER、MERCHANT、REVIEWER、SYSTEM的stream audience经过正反向权限测试。
- [ ] `SEC-005` **P0** Python无Domain DB凭据，Java无Graph checkpoint写凭据。
- [ ] `SEC-006` **P0** Temporal Search Attributes、Workflow ID和日志不包含PII或证据正文。
- [ ] `SEC-007` **P0** Prompt injection不能修改System规则、tool allowlist、model设置和输出schema。
- [ ] `SEC-008` **P0** Tool调用必须匹配显式capability、参数schema、actor scope和Java服务端授权。
- [ ] `SEC-009` **P0** 多模态URL/data、文件类型、尺寸、hash和证据所有权经过验证。
- [ ] `SEC-010` **P0** Reasoning/chain-of-thought在provider响应、trace、stream、DB和日志中均不可见。
- [ ] `SEC-011` **P0** 日志、Trace和错误响应执行敏感字段掩码，不暴露密钥、原文或内部栈。
- [ ] `SEC-012` **P1** Secret、证书和数据库密码轮换不会中断长运行Workflow或破坏旧checkpoint。
- [ ] `SEC-013` **P1** Domain、Graph、Temporal和对象存储执行独立备份、保留和删除策略。
- [ ] `SEC-014` **P1** 跨租户、跨案件、跨参与方模糊测试未发现IDOR或上下文污染。
- [ ] `SEC-015` **P1** 安全审计覆盖签名失败、hash冲突、stale epoch、越权tool和数据泄露阻断事件。
- [ ] `SEC-016` **P0** 敏感Temporal payload reference启用经密钥轮换验证的加密codec，未授权可见性接口不能读取明文。

## 15. 审计和可观测性

- [ ] `OBS-001` **P0** Trace context贯穿HTTP、Outbox、Temporal、Activity、AgentRun、LangGraph、LCEL、LiteLLM。
- [ ] `OBS-002` **P0** 每个正式Agent Artifact有完整immutable execution manifest。
- [ ] `OBS-003` **P0** Manifest包含workflow/run、revision、graph/checkpoint、prompt hash、model、input/output hash、policy、usage和trace。
- [ ] `OBS-004` **P0** Manifest足以复核正式输入和版本，但不保存隐藏reasoning。
- [ ] `OBS-005` **P1** Dashboard覆盖command/outbox age、Temporal queue/history、AgentRun、Graph、model、SSE和projection lag。
- [ ] `OBS-006` **P1** 告警基于SLO burn rate、stuck age、heartbeat和queue lag，而非仅进程存活。
- [ ] `OBS-007` **P1** Outbox、pending command、stale Workflow、stale Activity、Graph lease、projection drift均有告警。
- [ ] `OBS-008` **P1** Provider latency、429/5xx、schema failure、repair、guardrail和cost按model profile可查询。
- [ ] `OBS-009` **P1** SSE replay lag、slow consumer、Redis miss和stream compaction失败有指标。
- [ ] `OBS-010` **P1** Runbook覆盖stuck Workflow、Activity heartbeat、Graph恢复、Provider outage、DB failover和projection修复。
- [ ] `OBS-011` **P1** 值班人员在不直接修改内部表的情况下完成一次演练恢复。
- [ ] `OBS-012` **P1** OTel Collector至少双副本，export queue、丢弃span/metric和后端不可用均有指标、告警及有界降级行为。

## 16. 版本、发布与回滚

- [ ] `REL-001` **P0** 每次AgentRun固定process、Workflow、Graph、Checkpoint、Prompt、Model、Schema、Policy和Tool版本。
- [ ] `REL-002` **P0** Temporal Worker Versioning和GraphRegistry保留活跃旧版本。
- [ ] `REL-003` **P0** 新合同先部署兼容reader，再部署writer，不进行破坏性单步发布。
- [ ] `REL-004` **P0** Graph checkpoint迁移只在显式安全边界执行，并有回滚或旧版本恢复方案。
- [ ] `REL-005` **P1** 新Workflow/Graph先在新room epoch canary，不隐式改变运行中案件。
- [ ] `REL-006` **P1** Shadow比较不产生第二个正式结果，不向用户展示影子输出。
- [ ] `REL-007` **P1** Temporal replay、合同兼容、Graph恢复和DB migration在发布流水线中成为门禁。
- [ ] `REL-008` **P1** Feature flag只控制入口和版本选择，不让Workflow replay读取未记录的可变值。
- [ ] `REL-009` **P1** 回滚保留新版本已写入字段，旧reader能够忽略或兼容。
- [ ] `REL-010` **P1** 清理旧Worker/Graph前查询确认无活跃Workflow、Child和thread引用。

## 17. 当前版本发布门禁

旧的 Phase 0-8 条目是已经完成的实施计划，不再作为待办清单。每个候选提交必须以同一
release identity 完成以下门禁；不得拼接不同提交、不同 activation 或不同模型配置的结果。

- [ ] `RELBASE-001` **P0** Graph 固定为 `all-rooms.production-runtime.v2` /
  `production-runtime-graph.2026-08-18.3` / `production-runtime-checkpoint.v2`。
- [ ] `RELBASE-002` **P0** 新 Intake epoch 固定 `PARALLEL_FRAMES_V1` 和
  `agent-stream.v4`；历史 `MONOLITHIC_V3` 只走已记录的兼容/回放路径。
- [ ] `RELBASE-003` **P0** 三个 Intake sibling Node、独立 checkpoint、exact-three
  Java admission/assembly/finalization 通过正向、单路失败、reset、replay 和邻接回归。
- [ ] `RELBASE-004` **P0** Evidence 固定 `evidence_room_context.v2`、
  `evidence_turn_stream.v3`、`evidence-turn-result.v3`，文字和图片来源均绑定授权 hash。
- [ ] `RELBASE-005` **P0** 所有模型路径解析为 `qwen3.8-flash`，thinking 关闭，strict
  JSON Schema 开启；不存在自由 JSON、模型降级或隐藏推理公开路径。
- [ ] `RELBASE-006` **P0** Java Flyway migration 至少包含当前上限 `V094`，Graph
  migration 至少包含当前上限 `G017`；旧 migration 不被改写或删除。
- [ ] `RELBASE-007` **P0** 使用 canonical fixture 从前端表单创建 fresh case，完整走通
  双方 Intake、Evidence、Hearing、人工 Review 和 Outcome，并保留 case ID 与关键截图/History。
- [ ] `RELBASE-008` **P0** UAT 全程没有重复正式消息、跨 actor 数据、stale projection、
  丢失命令、未授权工具执行或靠人工数据库修改解锁流程。
- [ ] `RELBASE-009` **P0** 当前 activation、worker、Graph、Prompt、Model、Schema 和
  artifact hash 完全匹配；任一版本漂移失败关闭。
- [ ] `RELBASE-010` **P0** Production Runtime/Temporal 候选开关保持显式授权；UAT 成功不自动
  修改默认生产路由、Current/Ramping version 或核心组件版本。
- [ ] `RELBASE-011` **P0** Temporal/数据库等核心组件升级具有单独批准、逐版本迁移、
  一致备份恢复和 rollback 证据；应用发布命令不得隐式升级它们。
- [ ] `RELBASE-012` **P1** 静态合同、Java/Python/OCR/frontend 聚焦测试、构建、Compose
  config、smoke-test 和 API/E2E/load 检查均绑定同一候选 commit。

## 18. 备份与灾难恢复

- [ ] `DR-001` **P0** Domain PostgreSQL开启PITR并完成指定时间点恢复演练。
- [ ] `DR-002` **P0** Graph PostgreSQL备份恢复后，活跃thread可从一致checkpoint继续。
- [ ] `DR-003` **P0** 对象存储启用版本化/复制，证据hash在恢复后保持一致。
- [ ] `DR-004` **P1** Temporal使用Cloud多区域能力或经过验证的自建DR方案。
- [ ] `DR-005` **P1** 恢复顺序明确：Domain DB、Temporal、Graph DB、对象存储、Java/Python Worker、Projection reconcile。
- [ ] `DR-006` **P1** 区域故障演练达到RPO 5分钟、RTO 30分钟目标。
- [ ] `DR-007` **P0** 恢复后不会把已完成外部动作再次执行。
- [ ] `DR-008` **P1** 备份加密、访问审计、保留周期和恢复责任人有正式记录。
- [ ] `DR-009` **P1** 至少每季度执行一次恢复演练并保存原始时间线和偏差报告。

## 19. 统一生产门禁场景

以下场景必须在同一个候选版本上完成，不能用不同提交的结果拼接：

- [ ] `GATE-001` **P0** 1,000房间 + 250 AgentRun + 2,500 SSE综合负载通过。
- [ ] `GATE-002` **P0** 综合负载期间执行Java、Python、Temporal Worker、Redis和LiteLLM pod故障注入。
- [ ] `GATE-003` **P0** 综合负载期间注入重复、乱序、延迟和hash冲突命令，无重复正式结果。
- [ ] `GATE-004` **P0** 综合负载期间执行Domain/Graph PostgreSQL failover，RPO为0。
- [ ] `GATE-005` **P0** 一次活跃Workflow/Graph版本滚动升级和回滚通过。
- [ ] `GATE-006` **P0** USER/MERCHANT/REVIEWER跨scope泄露扫描为零。
- [ ] `GATE-007` **P1** 60分钟稳态、30秒burst、30分钟恢复窗口全部满足SLO。
- [ ] `GATE-008` **P1** 24小时soak无资源泄漏、stuck queue、History和stream表异常增长。
- [ ] `GATE-009` **P1** 值班Runbook演练和区域DR演练完成。
- [ ] `GATE-010` **P0** 架构负责人、Java负责人、Python负责人、SRE、安全和业务审核负责人共同签字。

## 20. 发布证据表模板

| Check ID | 状态 | Evidence/报告路径 | Owner | 执行时间 | 备注/豁免 |
| --- | --- | --- | --- | --- | --- |
| `ARCH-001` | TODO |  |  |  |  |
| `TEMP-011` | TODO |  |  |  |  |
| `GRAPH-006` | TODO |  |  |  |  |
| `E2E-005` | TODO |  |  |  |  |
| `PERF-001` | TODO |  |  |  |  |
| `HA-010` | TODO |  |  |  |  |
| `SEC-014` | TODO |  |  |  |  |
| `RELBASE-007` | TODO |  |  |  |  |
| `GATE-010` | TODO |  |  |  |  |

实际发布时应由脚本生成包含所有Check ID的完整证据表，禁止只保留本模板示例行。

## 21. 架构追踪矩阵

| 架构章节 | 验证前缀 |
| --- | --- |
| §1-2 权威边界与原则 | `ARCH-*` |
| §3 容量与SLO | `ENV-*`, `PERF-*`, `GATE-*` |
| §4 逻辑架构 | `ARCH-*`, `ENV-*`, `E2E-*` |
| §5 状态所有权 | `ARCH-*`, `JAVA-*`, `GRAPH-*` |
| §6 Temporal拓扑 | `TEMP-*`, `ROOM-*` |
| §7 命令与事务 | `CONTRACT-*`, `JAVA-*`, `E2E-*` |
| §8 AgentRun | `RUN-*`, `STREAM-*` |
| §9 流式协议 | `STREAM-*`, `PERF-*` |
| §10 LangGraph | `GRAPH-*` |
| §11 LangChain | `LCEL-*` |
| §12 合同 | `CONTRACT-*` |
| §13 Java领域架构 | `JAVA-*`, `ROOM-*` |
| §14 高可用部署 | `ENV-*`, `HA-*`, `DR-*` |
| §15 Admission control | `PERF-*` |
| §16 故障语义 | `E2E-*`, `HA-*` |
| §17 安全 | `SEC-*` |
| §18-19 审计与观测 | `OBS-*` |
| §20 版本发布 | `REL-*` |
| §21 验证策略 | `GATE-*` |
| §22 当前实现与兼容 | `RELBASE-*`, `REL-*`, `TEMP-*`, `GRAPH-*` |
| §23 拒绝方案 | `ARCH-*`, `TEMP-*`, `GRAPH-*`, `E2E-*` |
| §24 框架决策 | `ARCH-*`, `REL-*` |
| §25 成功标准 | `E2E-*`, `HA-*`, `SEC-*`, `GATE-*` |
