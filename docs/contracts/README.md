# 生产 Contract Baseline v1

当前 `main` 统一定义为 **`production-contract-baseline.v1`**。这里的 `v1` 是生产发布
目录的版本，用来把同一次 UAT 验证过的 Graph、Stream、Room 和模型合同组合绑定在一起；
机器可读目录位于
[`contracts/catalog/production-baseline.v1.json`](../../contracts/catalog/production-baseline.v1.json)。

## 为什么不把所有协议重命名为 v1

具体 wire contract 的版本号描述不同协议的真实演进层级。例如 Intake 当前使用
`agent-stream.v4`，Evidence 结果使用 `evidence-turn-result.v3`，Hearing 状态机使用
`hearing_flow.v2`。这些版本号是持久化数据的 discriminator，不是产品发布号。

因此本基线采用两层版本语义：

1. `production-contract-baseline.v1`：当前生产合同组合的唯一统一版本。
2. 各 wire/schema 版本：从首次生产基线开始不可变的兼容和重放身份，保持原值。

不采用“无版本协议”。跨服务、持久化或可重放的 payload 必须携带明确版本；否则消费者
无法区分旧数据、新数据和同名但不同语义的结构，也无法安全 fail closed。

## 当前目录

| 领域 | Baseline v1 选择 |
| --- | --- |
| Graph | `all-rooms.production-runtime.v2` / `production-runtime-graph.2026-08-18.3` / `production-runtime-checkpoint.v2` |
| Graph command/result | `room-graph-command.v1` / `room-graph-result.v1` |
| Intake | `PARALLEL_FRAMES_V1` / `intake_turn_context.v3` / `agent-stream.v4` |
| Evidence | `evidence_room_context.v2` / `evidence_turn_stream.v3` / `evidence-turn-result.v3` |
| Hearing | `hearing_flow.v2` / `hearing_answer_bundle.v4` / `trial_dossier.v1` |
| Outcome | `outcome.v1` family / `outcome-workflow-start.v1` |
| Model | `qwen3.8-flash`，thinking 关闭，strict JSON Schema |
| Persistence | Domain `V094`，Graph `G017` |

## 变更规则

- 首次生产发布是 clean break：旧 `target-e2e` UAT 标识、数据库、Graph checkpoint 和
  Temporal History 不在兼容范围内；新环境必须使用全新持久化边界。
- 文案、注释或不改变协议语义的实现修复不重编号 wire contract。
- payload 字段、必填性、authority、哈希范围或重放语义变化时，新增 wire 版本并保留旧读者。
- 生产合同组合发生不兼容变化时新增 `production-contract-baseline.v2`，不得原地改写 v1。
- migration、旧 Schema、compatibility matrix 和 replay fixture 不因新基线发布而删除。
- 从本基线产生的 Java/PostgreSQL、Temporal History 与 Graph checkpoint 版本权威优先于
  本目录文字。

具体业务合同见 [Hearing Flow](hearing-flow-v2.md) 和 [Retry Taxonomy](retry-taxonomy.md)。
