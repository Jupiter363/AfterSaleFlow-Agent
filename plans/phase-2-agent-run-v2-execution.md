# Phase 2 AgentRun V2 多代理执行计划

## 1. 当前状态

```text
plan_status: READY
engineering_execution: ALLOWED_WITH_OFF_SHADOW_RESTRICTIONS
promotion_gate: MIG-001 PENDING
next_phase_permission: PHASE_2_ENGINEERING_ONLY
team_shape: primary + 3 delegated implementation agents
```

本文把总重构计划中的 Phase 2 拆成可直接派发的开发任务、文件所有权、集成波次和测试批次。
它不改变 `MIG-001=PASS` 的进入条件。只有以下任一条件成立后，主代理才可以创建 Phase 2
实现 worktree：

1. `MIG-001=PASS`，正式允许进入 Phase 2；或
2. 通过 ADR/计划变更明确批准仅在 `OFF/SHADOW` 下进行开发，并明确禁止切流。

未满足条件时只能维护计划、测试清单和非运行时代码审查，不得实现 Phase 2 runtime。

2026-07-19 已通过
[`ADR 0007`](../docs/architecture/adr/0007-phase-2-off-shadow-development-exception.md)
批准第 2 类例外。该批准只允许 Phase 2 工程实现与 synthetic `SHADOW` 验证，不改变
`MIG-001` promotion 状态，也不允许生产部署或切流。

## 2. Phase 2 目标和不可变约束

目标：把当前用多条 `AgentRun` 行表达 attempt 的 V1 模型，迁移为一个 logical run、多个 attempt、
唯一 committed final，并由 Temporal Agent Activity 成为 V2 唯一 executor。

必须同时满足：

- `(case_id, logical_idempotency_key)` 唯一。
- `(run_id, attempt_no)` 唯一且 attempt 单调递增。
- `(agent_run_attempt_id, sequence_no)` 唯一。
- 同一 logical run 最多一个 committed formal final。
- Python 已完成而 Java 丢响应时，从 command ledger 返回同一 result hash，不重跑模型。
- Java Finalizer 失败时只重试 Finalizer，不重跑模型。
- V2 只有 Temporal Activity 执行；legacy scheduler 对 V2 只能是 `DETECTOR` 或 `OFF`。
- `agent-stream.v2` durable append 先于 live fan-out。
- partial visible output 失败后必须发送 `attempt_reset`，不能拼接两个 attempt。
- Redis 只负责 wake-up；PostgreSQL replay 是权威事实。
- 旧 V1 run 和进行中的 V1 epoch 必须继续可读、可完成、可回滚。

非目标：不迁移 LangGraph cognitive state，不切换任何正式房间 graph writer，不删除 V1 reader，
不在本阶段进行 1,000 房间完整负载或生产切流。

## 3. 团队和权限

| 角色 | 代码责任 | 测试责任 | 禁止事项 |
| --- | --- | --- | --- |
| 主代理 R | 共享合同、Coordinator/Lifecycle/Finalizer、scheduler mode、集成与门禁 | 分配 test token，运行 T1-T3 batch，归档证据 | 不重复实现子代理 owned paths |
| 子代理 A | V041、logical/attempt/manifest persistence、durable stream/high-watermark storage | 编写实体、repository、migration 和 persistence 测试 | 不改 Coordinator、Temporal Activity、Python、Frontend |
| 子代理 B | `ExecuteAgentRunActivity`、heartbeat、cancel、retry budget、agent worker registration | 编写 Activity、completion-loss、worker recovery 测试 | 不改数据库实体、stream public protocol、legacy scheduler |
| 子代理 C | Java/Python stream v2 adapter、coalescer、Vue dual reader/reset/reconnect | 编写协议、隐私、reset、slow-consumer 和 UI 测试 | 不改 migration、Coordinator、Finalizer、Spring 主配置 |

所有子代理拥有其 assigned worktree 和 owned paths 内的直接编辑、创建测试、运行获准聚焦检查和提交权限，
无需逐文件请求用户批准。越过 owned paths、修改共享合同、执行破坏性操作、访问密钥或生产环境时必须停止并
交还主代理。

## 4. Worktree 和分支

进入实现前由主代理固定同一个 `BASE_COMMIT`，确认工作区干净，并创建：

| 代理 | 分支 | Worktree |
| --- | --- | --- |
| A | `codex/p2-a-agentrun-persistence` | `.worktrees/p2-a-agentrun-persistence` |
| B | `codex/p2-b-temporal-executor` | `.worktrees/p2-b-temporal-executor` |
| C | `codex/p2-c-stream-v2` | `.worktrees/p2-c-stream-v2` |

约束：

- 三个分支必须从主代理提交的同一个 P2.0 contract pack 开始。
- 子代理不得 merge 主分支；只允许在收到主代理指令后 rebase，或由主代理 cherry-pick。
- 每个普通提交建议不超过 25 个文件和 2,000 行手写变化；超出时拆分或在交付中记录例外。
- 每个提交必须保持可编译边界；临时 stub 只能位于 owned paths，且下一 batch 前必须移除。

## 5. 文件所有权

### 5.1 主代理 R owned paths

```text
contracts/agent-platform/v1/agent-stream-event.schema.json
contracts/agent-platform/v1/fixtures/**
java-api-service/src/main/java/com/example/dispute/workflow/contract/v1/AgentStreamEvent.java
java-api-service/src/main/java/com/example/dispute/workflow/contract/v1/AgentExecutionManifest.java
java-api-service/src/main/java/com/example/dispute/agentstream/application/AgentRunCoordinator.java
java-api-service/src/main/java/com/example/dispute/agentstream/application/AgentRunLifecycleService.java
java-api-service/src/main/java/com/example/dispute/agentstream/application/AgentRunWorker.java
java-api-service/src/main/java/com/example/dispute/agentstream/application/AgentRunFinalizationContext.java
java-api-service/src/main/java/com/example/dispute/agentstream/application/AgentRunFinalizer*.java
java-api-service/src/main/java/com/example/dispute/agentstream/application/AgentRunRecoveryScheduler.java
java-api-service/src/main/java/com/example/dispute/workflow/config/TemporalWorkerConfiguration.java
java-api-service/src/main/java/com/example/dispute/workflow/config/TemporalWorkerProperties.java
java-api-service/src/main/resources/application*.yml
docs/runbooks/temporal-first/phase-2-*.md
scripts/*phase*2*
```

主代理也是所有共享接口、feature flag、scheduler mode 和跨模块 transaction boundary 的最终 owner。

### 5.2 子代理 A owned paths

```text
java-api-service/src/main/resources/db/migration/V041*
java-api-service/src/main/java/com/example/dispute/infrastructure/persistence/entity/AgentRun*.java
java-api-service/src/main/java/com/example/dispute/infrastructure/persistence/repository/AgentRun*.java
java-api-service/src/main/java/com/example/dispute/agentstream/infrastructure/persistence/**
java-api-service/src/main/java/com/example/dispute/agentstream/infrastructure/delivery/**
java-api-service/src/main/java/com/example/dispute/workflow/infrastructure/persistence/entity/AgentExecutionManifestEntity.java
java-api-service/src/main/java/com/example/dispute/workflow/infrastructure/persistence/repository/AgentExecutionManifestRepository.java
java-api-service/src/test/java/com/example/dispute/agentstream/persistence/**
java-api-service/src/test/java/com/example/dispute/agentstream/delivery/**
```

### 5.3 子代理 B owned paths

```text
java-api-service/src/main/java/com/example/dispute/workflow/activity/agent/**
java-api-service/src/main/java/com/example/dispute/workflow/temporal/agentrun/**
java-api-service/src/test/java/com/example/dispute/workflow/agentrun/**
```

Agent B 返回 Activity registration requirements，由主代理修改现有 `TemporalWorkerConfiguration`；
不得自行创建第二套 Agent Worker 配置。

### 5.4 子代理 C owned paths

```text
java-api-service/src/main/java/com/example/dispute/agentstream/infrastructure/AgentNdjsonStreamClient.java
java-api-service/src/main/java/com/example/dispute/agentstream/application/AgentStreamFrame.java
java-api-service/src/main/java/com/example/dispute/agentstream/application/AgentRunStreamEventService.java
java-api-service/src/test/java/com/example/dispute/agentstream/*Stream*Test.java
python-agent-service/app/streaming.py
python-agent-service/tests/test_streaming*.py
frontend/src/api/agentStream.js
frontend/src/api/agentStream.test.js
frontend/src/stores/agentStream.js
frontend/src/stores/agentStream.test.js
frontend/src/components/room/AgentStreamingMessage.vue
frontend/src/components/room/AgentStreamingMessage.test.js
```

不在 owned paths 内的修改必须先由主代理重新分配。子代理不得通过扩大 glob 绕过所有权。

## 6. 执行波次和依赖

### Wave 0：主代理合同包 P2.0

主代理先完成一个小提交，只冻结接口，不实现完整业务：

- 复核既有 `agent-stream.v2` JSON Schema、Java record 和 Pydantic model，不兼容变化必须先走 ADR。
- 定义 logical run、attempt、manifest、executor mode、Finalizer receipt 的 Java port/record。
- 固定 V041 expand/backfill/rollback 约束和 planned test class names。
- 固定 `protocol-default=v1|v2`、`scheduler-mode=EXECUTOR|DETECTOR|OFF` 的读取与持久化边界。
- 为 A/B/C 生成相同 `BASE_COMMIT` 和 task brief。

P2.0 只运行合同 fixture 和静态检查，不运行全仓测试。

### Wave 1：三个开发闭环并行

| Task | Owner | 内容 | 依赖 | Check IDs | 交付 |
| --- | --- | --- | --- | --- | --- |
| P2-A1 | A | V041 expand/backfill、logical run、attempt、manifest、唯一约束 | P2.0 | RUN-001..003, RUN-009 | migration + entity/repository + tests commit |
| P2-B1 | B | ExecuteAgentRunActivity、10m StartToClose、15s HeartbeatTimeout、5s progress、cancel；向主代理交付 registration requirements | P2.0 ports | RUN-004, RUN-005, RUN-007, HA-002 | Activity + pure/recovery tests commit |
| P2-C1 | C | Java/Python v2 parse/encode、allowlist、delta coalescer、v1 adapter | P2.0 schema | STREAM-001, STREAM-004..007 | adapters + contract/privacy tests commit |

三个代理完成代码后先提交，不自行运行重型测试。主代理按 A -> B -> C 顺序 cherry-pick 到 integration branch，
运行 Batch `P2-BATCH-1`。

### Wave 2：正式结果、恢复和 UX 并行

Batch 1 通过后：

| Task | Owner | 内容 | 依赖 | Check IDs | 交付 |
| --- | --- | --- | --- | --- | --- |
| P2-R1 | R | Coordinator/Lifecycle logical reuse、Finalizer fence/transaction、scheduler三态 | A1+B1+C1 | RUN-006, RUN-008, JAVA-007..010, E2E-006 | central integration commit |
| P2-A2 | A | durable stream batch、high-watermark、Redis wake-up storage、retention manifest | A1+C1 contract | STREAM-008, STREAM-010..013, HA-007 | persistence/delivery commit |
| P2-B2 | B | Python final后断链、completion-loss、迟到旧attempt final、worker kill | B1+R1 ports | RUN-005..007, E2E-004..005, HA-002 | fault-injection tests + fixes commit |
| P2-C2 | C | Vue dual reader、attempt_reset、abort/reconnect、slow-consumer有界行为 | C1 | STREAM-002, STREAM-003, STREAM-006, STREAM-009, UI-004 | frontend/runtime tests commit |

P2-R1 的共享 port 变化先由主代理提交，A/B/C 只在收到新 base 指令后 rebase。Wave 2 合并后运行
Batch `P2-BATCH-2`。

### Wave 3：交叉审查和修复

- A 审查 B：attempt/retry 是否绕过唯一约束或重复模型。
- B 审查 C：heartbeat/cancel/reset 时序是否与 Activity lifecycle 一致。
- C 审查 A：durable event、cursor 和 replay 是否满足前端/Python协议。
- 主代理审查所有 transaction、fence、executor mode、rollback 和 Check ID 覆盖。

审查只在各代理已完成实现后执行。问题由原 owner 修改并提交，不允许 reviewer 越权改 owner 文件。
主代理随后完成 P2-R2：冻结 V1/V2 selector、运行 application-context 与兼容性检查、生成 Check ID
coverage 和 phase metrics，并固定唯一 candidate commit。完成后运行 Batch `P2-BATCH-3`。

## 7. 子代理任务单模板

主代理派发时必须填写：

```text
task_id:
base_commit:
objective:
owned_paths:
forbidden_paths:
input_contracts:
required_check_ids:
required_tests_to_write:
allowed_local_checks:
tests_deferred_to_batch:
definition_of_done:
commit_message:
```

任务消息必须明确写出：“你是实现 owner，直接编辑并提交，不要只给审查建议。”

子代理完成时必须返回：

```text
task_id:
commit:
changed_paths:
tests_added_or_updated:
checks_run:
tests_deferred_to_batch:
check_ids_covered:
shared_contract_impact:
remaining_risks:
```

缺少 commit 或实现代码不算完成。子代理不得 stage 用户或其他代理的无关变化。

## 8. 集成和提交纪律

- 主代理持续 cherry-pick，不等待整个 Wave 全部结束后才第一次集成。
- 每次只集成一个 agent commit，失败时可以精确定位 owner 和 slice。
- 共享合同冲突由主代理解决，不能让两个子代理各自选择兼容策略。
- cherry-pick 后只运行 test matrix 选出的去重集合。
- Batch 失败先分类为 PRODUCT、FIXTURE、INFRA 或 EXTERNAL_GATE，再决定修复和复跑范围。
- 不允许通过扩大 retry、删除断言、吞掉异常或改为 mock 来获得绿色。
- 每个 Batch 固定 candidate commit，测试证据不能跨 commit 拼接。

## 9. CPU 和测试调度

测试规则的机器可读版本见
[`phase-2-agent-run-v2-test-batches.yaml`](./phase-2-agent-run-v2-test-batches.yaml)。

本地并发硬限制：

```text
active_primary_agents: 1
active_child_agents: 3
heavy_test_slots: 1
light_test_slots: 2
playwright_workers: 1
docker_compose_owners: primary-only
temporal_test_environment_owners: primary-only for batch tests
```

Test token 规则：

1. 子代理可以自行运行无容器、3 分钟内完成的 T0 检查。
2. 需要 Maven test、Spring context、Temporal Test Server、PostgreSQL、Redis、Docker 或浏览器时，先向主代理发送
   `TEST_REQUEST(task_id, command, expected_duration, resource_class)`。
3. 主代理只向一个 heavy requester 发放 `TEST_TOKEN`。其他代理继续编码或审查，不启动重型进程。
4. token owner 完成后返回 exit code 和报告路径；主代理释放 token。
5. Maven 全量、Docker Compose、Playwright 全量和统一回归永远由主代理执行。

## 10. 完成标准

Phase 2 engineering checkpoint 只有同时满足以下条件才能为 PASS：

- P2.1-P2.8 全部 slice 已集成，所有 owner review 关闭。
- `RUN-001..009`、`STREAM-001..013`、`JAVA-007..010`、`E2E-004..006`、
  `HA-001..002`、`HA-007` 有同一 candidate commit 的证据。
- `CORE-004..009`、`SEC-001..002`、`SEC-004`、`UI-004` 在 V1/V2 双读、角色切换、
  public allowlist、final/reset 和固定画布场景中保持基线行为。
- logical final 唯一，四个 crash window 不重跑已完成模型、不重复 Finalizer。
- V1 reader 和进行中的 V1 run 仍兼容；V2 reader reset/reconnect 正确。
- 同一 logical queue 只有一个 executor；scheduler mode 可回滚但不能复活 V1 executor 消费 V2。
- Batch 1-3 全部通过并生成 `phase-metrics.json`。
- `MIG-002` promotion evidence 和责任人批准完成前，promotion gate 仍为 PENDING。

阶段报告必须输出：

```text
engineering_checkpoint: PASS | FAIL
promotion_gate: PASS | PENDING | FAIL
next_phase_permission: ALLOWED | BLOCKED
```
