# Phase 1 Temporal 控制面实施复盘

## 1. 文档目的

本文记录 Phase 1 `Temporal control-plane foundation` 实施过程中实际出现的问题、根因、
修复方式和后续阶段必须执行的预防措施。它面向 Phase 2-8 的开发、审查和验收人员，
用于阶段开始前预检、阶段进行中的故障定位，以及阶段结束时复核是否再次出现相同问题。

本文不是 `MIG-001` 的通过证明，也不替代正式证据包、发布审批或
[`phase-1-mig-001.md`](./phase-1-mig-001.md)。
日常开发只需先读
[`phase-1-lessons-quick-reference.md`](./phase-1-lessons-quick-reference.md)，
需要完整证据和根因时再读本文。

## 2. 结论与状态边界

Phase 1 已完成代码和本地验证检查点，但正式生产门禁仍未完成：

- 已建立 durable command intake、command/bootstrap outbox、Case/Room Workflow、
  projection fencing、reconciliation、独立 Task Queue、恢复测试和 replay 测试。
- Java、Python、OCR、Frontend、PostgreSQL、Temporal recovery/replay 和 Compose restart
  已在本地检查点通过。
- `MIG-001` 仍保持未勾选。真实 KMS、private ACL、synthetic scenario driver、不可变证据存储
  和责任人审批未完成前，不得把本地技术通过描述为生产切流批准。
- 按当前计划，`MIG-001=PASS` 仍是 Phase 2 的进入条件。若要允许后续代码仅在
  `OFF/SHADOW` 下并行开发，必须先通过 ADR 或计划变更正式拆分开发门禁与生产门禁，
  不能口头绕过。

## 3. 可审计时间线

| 节点 | Commit | 时间（Asia/Shanghai） | 说明 |
| --- | --- | --- | --- |
| Phase 0 gate evidence | `c24362ff` | 2026-07-17 14:14:31 | Phase 1 计算起点 |
| 首个 Phase 1 runtime 提交 | `4dff1ab8` | 2026-07-17 15:22:33 | persistence foundation |
| 初轮 recovery/replay gate | `676ad33c` | 2026-07-18 01:12:51 | 前八个 Phase 1 提交结束 |
| 生产闭环 hardening | `41a0d419` | 2026-07-18 23:33:48 | allocator、bootstrap、recovery、证据归档等集中补齐 |
| Spring 全上下文修复 | `4dc6c1cf` | 2026-07-19 00:25:56 | Phase 1 核心代码检查点 |
| 前端基线修复 | `d734f5ae` | 2026-07-19 01:31:13 | 工作区与统一回归检查点 |

时间口径：

- Phase 1 入口至统一检查点：`35 小时 16 分 42 秒`。
- 首个 runtime 提交至统一检查点：`34 小时 08 分 40 秒`。
- 中间存在 `22 小时 20 分 57 秒` 无提交窗口。Git 无法判断其中是暂停还是未提交开发，
  因此不能把 commit 时间直接当作有效工程工时。
- 若把该窗口全部视为暂停，可见提交会话约 `12 小时 56 分`。准确的有效工时只能在后续阶段
  通过自动生成的 phase metrics 记录。

## 4. 规模与验证事实

Phase 1 核心范围 `c24362ff..4dc6c1cf`：

- `11` 个提交。
- `233` 个文件变化。
- 新增 `36,658` 行，删除 `296` 行。
- 测试新增约 `16,426` 行，占新增量 `44.8%`。
- `41a0d419` 单个 hardening 提交触及 `137` 个文件，新增 `21,429` 行，占 Phase 1
  新增量 `58.5%`。

最终本地检查点：

| 验证层 | 结果 | 已知耗时或说明 |
| --- | --- | --- |
| Java | `524/524` | Surefire report window 约 4 分 07 秒 |
| Python Agent | `259/259` | 通过 |
| OCR | `13/13` | 通过 |
| 根静态契约 | `100/100` | 约 6 秒 |
| Frontend unit | `405/405` | 约 39 秒 |
| Frontend build | PASS | 约 11 秒 |
| PostgreSQL Phase 1 | `48/48` | 通过 |
| Temporal replay/recovery | `12/12` | 通过 |
| Activity completion-loss | `1/1` | 通过 |
| Compose Temporal restart | `1/1` | 通过 |
| Playwright | `125/126`，失败场景复跑 `1/1` | 唯一失败为本地 `ERR_NO_BUFFER_SPACE`，不是产品断言失败 |

结论：单次自动化运行并不是主要耗时来源。主要成本来自范围过大、生产闭环在后期集中发现、
历史浏览器基线漂移，以及修复后重复审查和验证。

## 5. 做得正确的部分

1. **保持 fail-closed**：缺少生产证据时没有勾选 `MIG-001`，也没有进入 Phase 2。
2. **故障窗口覆盖充分**：覆盖 command/outbox、旧 fence、completion-loss、worker restart、
   replay 和 reconciliation，而不是只验证 happy path。
3. **控制面和模型面隔离**：API、control worker、agent worker 及四条 Task Queue 的职责得到明确拆分。
4. **测试与实现同步增长**：约 44.8% 的新增代码属于测试，关键恢复语义有可重复验证载体。
5. **最终工作区可审计**：相关变化独立提交，统一检查点结束后工作区保持干净。

## 6. 问题、根因与防复发规则

### RET-P1-001：阶段范围过大

**现象**：Phase 1 核心修改 233 个文件并新增 36,658 行，单个阶段同时覆盖数据库、命令接收、
Workflow、projection、部署、observability、recovery、证据归档和大量测试。

**根因**：计划按横向基础设施能力分成 P1.1-P1.8，但开发提交没有始终形成可以独立验证的纵向闭环；
早期 skeleton 与正式生产闭环之间存在大量隐含工作。

**影响**：审查面过宽，集成风险在阶段末叠加，任何基础合同变化都会跨越大量文件。

**防复发规则**：

- 后续每个阶段拆成 4-6 个纵向 slice，每个 slice 同时包含合同、实现、恢复测试和证据入口。
- 单个普通提交建议不超过 25 个文件和 2,000 行手写变化。migration、生成代码和 fixture
  可单独统计，但仍必须独立审查。
- 一个 slice 未通过独立 review 和聚焦验证前，不开始依赖它的下一个 slice。

### RET-P1-002：生产 hardening 集中在巨型提交

**现象**：`41a0d419` 占 Phase 1 新增量 58.5%，一次性补入 room epoch allocator、bootstrap outbox、
scan claim、domain event recovery、scheduler、恢复测试和证据归档器。

**根因**：前八个提交证明了各组件可以存在，但没有足够早地从“接收一个真实形状的 synthetic command”
向下走完整条链路，因此 allocator、bootstrap、recovery 和 cutover readiness 的缺口在综合 review 时才暴露。

**影响**：review 无法逐个隔离不变量，修复与新增能力混在同一提交，回归定位成本升高。

**防复发规则**：每个阶段的第一个实现 slice 必须是一条最小端到端 synthetic vertical slice；
其余 slice 只能扩展已经跑通的链路，不能等到阶段末再第一次组装完整上下文。

### RET-P1-003：Spring 全上下文装配验证过晚

**现象**：聚焦测试通过后，完整 Spring context 才发现 bootstrap components 未正确装配，最终由
`4dc6c1cf` 修复。

**根因**：配置类、profile 和组件扫描分别有单元测试，但缺少跟随 worker/profile 变更执行的最小
`@SpringBootTest` application-context smoke。

**影响**：局部正确的组件在真实启动图中不可用，问题只能在完整验证阶段出现。

**防复发规则**：任何 Spring profile、bean、scheduler、Temporal Worker registration 或配置属性变化，
都必须在同一 slice 运行对应 profile 的 context smoke；不得只依赖 mock 单元测试。

### RET-P1-004：开始时没有认证浏览器基线

**现象**：最终 Playwright 首轮只有 `33/126`。比较 `e8f22987` 后确认 Phase 0/1 没有引入相应前端变化，
失败来自更早 UI 演进后测试 fixture 和断言未同步。

**根因**：重构开始时记录了功能基线，但没有证明当前候选 commit 的浏览器基线为绿色，也没有登记
已知红项。

**影响**：Phase 1 结束时承担了历史前端债务，额外修改 12 个文件并延长统一检查点约一小时。

**防复发规则**：

- 阶段开始优先复用上一个已签名、同基线 commit 的绿色报告。
- 若报告缺失、commit 不一致或测试资产已变化，先运行一次基线认证再写阶段代码。
- 已知失败必须登记 owner、原因和 disposition，不能留到阶段结束时判断是否由本阶段引入。

### RET-P1-005：本地浏览器环境产生假失败

**现象**：Playwright 统一运行中，`/src/styles.css` 加载返回 Windows
`net::ERR_NO_BUFFER_SPACE`，页面为空；相同 `681px` 场景随后复跑通过。

**根因**：本地存在多个长期 Node/Vite 进程，浏览器回归与共享 Windows 网络资源缺少统一生命周期管理。

**影响**：产生一次非产品失败，需要人工读取 screenshot、trace 和 network event 才能排除代码回归。

**防复发规则**：

- 浏览器统一检查点使用干净的 Linux/WSL2 runner 或一次性容器。
- runner 启动前检查固定端口、遗留 Node/Chromium 进程和可用系统资源。
- Playwright server 由一个 owner 启停，禁止多个并行任务各自启动 Vite。
- 不用盲目 retry 掩盖问题；先保存 trace 并分类为 PRODUCT、FIXTURE 或 INFRA，再只复跑失败场景。

### RET-P1-006：工程完成和生产批准使用同一个“完成”表述

**现象**：本地测试全部通过后，仍缺真实 KMS、ACL、synthetic driver 和 promotion attestation；
如果只说“Phase 1 完成”，读者会误认为可以进入 Phase 2 或生产切流。

**根因**：计划只有 `MIG-001=PASS` 一个退出词汇，但实施过程同时需要表达“代码可审查”和
“生产可推广”两个不同状态。

**影响**：排期、技术状态和发布权限容易混淆，也会让外部审批等待被错误计算为开发耗时。

**防复发规则**：所有阶段报告必须同时输出以下三项，禁止省略：

```text
engineering_checkpoint: PASS | FAIL
promotion_gate: PASS | PENDING | FAIL
next_phase_permission: ALLOWED | BLOCKED
```

在 ADR 正式批准前，这三个字段只改善报告语义，不改变现有 `MIG-00x` 进入条件。

### RET-P1-007：验证证据和耗时数据没有统一自动归档

**现象**：JUnit、命令输出、Temporal 测试结果和浏览器 trace 分散在各模块 target/test-results 与任务记录中；
Git 时间只能给出 13-35 小时的范围，不能准确拆分开发、机器等待、返工和外部门禁等待。

**根因**：Phase 1 建立了正式 evidence archiver，但缺少面向日常开发检查点的轻量 metrics/evidence runner。

**影响**：复盘依赖人工还原，无法持续比较后续阶段效率。

**防复发规则**：后续阶段必须由统一 runner 生成 `phase-metrics.json`，至少记录：

- phase、slice、commit、dirty state。
- command、开始/结束时间、exit code、JUnit/trace 路径和 SHA-256。
- PRODUCT、FIXTURE、INFRA、EXTERNAL_GATE 四类失败和累计返工时间。
- engineering checkpoint 与 promotion gate 分别耗时。

## 7. Phase 2-8 强制执行清单

### 7.1 阶段开始前

- [ ] 阅读本复盘、目标 Phase 计划、上一阶段 evidence 和当前功能基线。
- [ ] 确认上一阶段 `engineering_checkpoint`、`promotion_gate` 和 `next_phase_permission`。
- [ ] 记录 branch、HEAD、开始时间和干净工作区状态。
- [ ] 证明浏览器及跨服务基线报告与当前基线 commit 一致；不一致时先认证或登记已知失败。
- [ ] 把本阶段拆成 4-6 个纵向 slice，标明唯一 owner、文件边界、输入输出合同和回滚点。
- [ ] 为每个 slice 写明 L0/L1/L2 聚焦命令，禁止开发完成后才决定如何验证。
- [ ] 标出必须由真实环境或责任人完成的 external gate，不把它混入编码工时。

### 7.2 每个 slice 进行中

- [ ] 先增加失败合同或测试，再完成最小实现。
- [ ] 只运行受影响的静态、单元、合同和恢复测试，不重复运行全仓回归。
- [ ] 配置、profile 或 bean 变化时运行完整 application-context smoke。
- [ ] 完成独立 code review，并解决问题后再开始依赖 slice。
- [ ] 自动归档命令、耗时、退出码和报告 hash。
- [ ] 若变化超过 25 个文件或 2,000 行手写代码，拆分提交或记录书面例外理由。

### 7.3 阶段边界

- [ ] 固定唯一 candidate commit，确认工作区干净。
- [ ] 运行本阶段要求的合同、恢复、数据库和服务集成验证。
- [ ] 复核 writer、epoch、fence、idempotency、retry 和 rollback 不变量。
- [ ] 只在约定统一检查点运行全仓和浏览器全量；失败后先分类，再决定复跑范围。
- [ ] 生成 evidence bundle 和 phase metrics，不从不同 commit 拼接通过结果。
- [ ] 明确报告工程检查点、生产门禁和下一阶段权限，不用单一“已完成”代替。

## 8. 故障分类与复跑规则

| 类型 | 判定 | 处理 |
| --- | --- | --- |
| PRODUCT | 代码、合同、migration 或业务断言错误 | 修复后运行受影响测试，再在阶段边界运行规定集合 |
| FIXTURE | mock、测试数据、断言与已批准产品合同漂移 | 先证明产品合同，再更新 fixture；禁止为了变绿降低安全断言 |
| INFRA | 端口、容器、网络、磁盘、进程或 runner 失败 | 保存证据、恢复环境，只复跑失败场景；不得修改产品代码迎合环境 |
| EXTERNAL_GATE | KMS、ACL、证书、生产拓扑或签名审批缺失 | 工程状态可记录，promotion 保持 PENDING，禁止伪造或跳过 |

同一失败在没有新证据或修复的情况下不得连续重跑全量。精确失败场景通过后，只有共享代码、全局 fixture
或运行拓扑发生变化时才重新运行更大集合。

## 9. 后续阶段时间预算建议

对与 Phase 1 复杂度接近、但已按纵向 slice 拆分的阶段，本地工程检查点目标为 7-9 个有效工作小时：

| 活动 | 目标时间 |
| --- | --- |
| 入口预检、合同和 slice 划分 | 30-45 分钟 |
| 4 个纵向 slice | 每个 60-90 分钟 |
| 分段 review 与修复 | 90-120 分钟 |
| 阶段聚焦验证与证据归档 | 30-60 分钟有效操作时间 |

该预算不包含外部审批、生产环境排队、soak、PITR 或区域 DR。外部门禁必须独立计时，避免错误评价工程效率。

## 10. 参考资料

- [Temporal + LangGraph 房间重构计划](../../../plans/temporal-langgraph-room-refactor.md)
- [Temporal-first 生产验证清单](../../acceptance/temporal-first-agent-platform-verification-checklist.md)
- [Phase 1 MIG-001 SHADOW 证据归档与切流手册](./phase-1-mig-001.md)
- [Temporal-first Agent Platform Architecture](../../architecture/temporal-first-agent-platform.md)
- [当前房间功能基线](../../acceptance/current-room-function-baseline.md)
