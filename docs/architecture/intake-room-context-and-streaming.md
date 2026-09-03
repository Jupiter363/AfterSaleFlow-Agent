# 接待室 V4 三 Frame 并行 Graph 架构

- 状态：当前实现基线
- 更新：2026-09-04
- 代码基线：`main@10526e58b954498f69bae00ea709f6f9e4981971`
- UAT 基线：`CASE_P9_6A98633E_11` 六站全链路完成

本文描述当前接待室实现。旧的 V3 单体输出仍作为已记录执行的回放兼容边界，
不再是新目标 Intake epoch 的设计说明。

## 1. 当前版本身份

| 绑定 | 当前值 |
| --- | --- |
| Graph key | `all-rooms.target-e2e.v2` |
| Graph version | `target-e2e-graph.2026-08-18.3` |
| Checkpoint schema | `target-e2e-checkpoint.v2` |
| 新 Intake execution profile | `PARALLEL_FRAMES_V1` |
| 历史兼容 profile | `MONOLITHIC_V3` |
| 并行公开流 | `agent-stream.v4` |
| 模型 | `qwen3.8-flash` |
| Provider 输出 | strict JSON Schema |
| thinking | 关闭 |

Execution profile 在 room epoch 建立时固定。已经记录为 `MONOLITHIC_V3` 的执行只按
原协议回放，不在同一 epoch 内热切换；新目标 Intake epoch 使用
`PARALLEL_FRAMES_V1`。Evidence、Hearing、Review 和 Outcome 继续使用各自的
`agent-stream.v3` 路径。

## 2. 权威边界

接待室把“模型生成内容”和“正式业务状态”分开：

```text
Java 冻结身份、案件、epoch、fence、消息与矩阵
  -> Java 原子准入 exact-three Frame manifest
  -> Python 三个兄弟 Graph Node 并行生成
  -> Java 按 Frame 持久化 provisional item
  -> Java 收齐三个 sealed Frame
  -> Java 确定性 Assembler
  -> Java Finalizer 提交正式消息、卷宗与阶段
```

- Temporal 负责持久流程、等待、重试、取消和 room epoch。
- Java/PostgreSQL 是正式消息、案情矩阵、阶段、命令结果和审计的唯一写入者。
- Python/LangGraph 只产生受版本约束的 Frame proposal 和技术 checkpoint。
- 模型不决定正式总分、下一阶段、幂等键、ID、hash、actor scope、epoch 或 fence。
- 前端只展示 durable provisional projection 和正式投影，不从文本猜测状态。

## 3. Exact-three 父图

Python 顶层 `StateGraph` 只有三个从 `START` 同时出发、分别直达 `END` 的兄弟
Node：

```text
                     -> dialogue_frame  ->
START (同一上下文)  -> dossier_frame   -> END
                     -> quality_frame   ->
```

父图没有语义 join、Assembler 或 Proposal 节点。每个父 Node 只调用对应的 lane-local
子图，并写入互不重叠的 outcome channel。父图的 END barrier 仅表示本次技术调用结束，
不会推迟任一路已通过校验的公开 item。

每个 lane-local 子图均采用：

```text
authorize_input
  -> invoke_model
  -> [必要时 generation reset -> invoke_replacement_model]
  -> checkpoint_terminal
```

单路 Schema 失败只允许该路进入有界 replacement generation；已 sealed 的兄弟 Frame
不会再次调用模型。三个 Frame 使用独立 checkpoint namespace、generation、frame ID、
Prompt 和输出 Schema。

## 4. 一次冻结、三路复用

Java 在 Provider 调用前冻结同一份业务快照。服务端信封包含 case、room、actor、
source event、epoch、fence、command、snapshot/hash 等权威；Provider 只接收脱敏后的
model view。

Model view 以稳定顺序包含：

1. 当前参与方的诉讼身份与可写分区；
2. 上一轮正式 phase、案情和质量投影；
3. 当前动作绑定；
4. 冻结事实矩阵；
5. 当前参与方最近私有对话；
6. 当前原始消息；
7. 该 Frame 专属任务和输出合同。

两方私聊原文不交叉；tenant、case ID、actor ID、fence、内部引用、凭证和写入能力不
进入 Provider 内容。三路输入共享同一 `model_context_view_sha256`，但各自拥有独立的
`frame_model_input_sha256` 和 `frame_prompt_sha256`。

## 5. Frame 职责

| Frame | Prompt / sealed schema | 唯一职责 | 明确禁止 |
| --- | --- | --- | --- |
| `DIALOGUE_FRAME` | `intake_turn_dialogue_frame` / `intake-dialogue-frame.v4` | 候选确认、过渡或备注回应；仅在等待备注时区分 remark disposition | 写卷宗、评分、模型自造追问或正式动作 |
| `DOSSIER_FRAME` | `intake_turn_dossier_frame` / `intake-dossier-frame.v4` | 当前来源的 typed fact/position delta | 评分、阶段推进、覆盖另一方冻结立场 |
| `QUALITY_FRAME` | `intake_turn_quality_frame` / `intake-quality-frame.v2` | 六个固定维度分数及最多六个缺口候选 | 写事实、公开回复、输出独立总分 |

六个质量维度顺序和上限固定：

| 维度 | 上限 |
| --- | ---: |
| `REFERENCES` | 15 |
| `EVENT_STORY` | 20 |
| `PARTY_POSITIONS` | 20 |
| `REQUESTED_RESOLUTION` | 15 |
| `RISK_AND_CONFLICTS` | 15 |
| `NEXT_ACTION_CLARITY` | 15 |

Provider 只输出各维度分数。Java 按固定顺序校验并求和；缺口必须绑定当前冻结矩阵允许的
`FACT_` 键。对外问题和“下一步核验重点”必须使用面向当事人的中文业务表达，英文
字段名只是协议标识，不得作为 UI 文案直接输出。

## 6. 准入、流与持久化

Java 在任何 Provider 调用前原子准入 exactly three manifests。每个 manifest 绑定：

- frame type、generation 和 frame ID；
- Prompt、Schema 与 Model profile；
- command、source event、actor scope、room epoch 与 fence；
- context/model input/prompt SHA-256；
- 统一 turn deadline。

准入失败时三路均不得开始。通过后，每个 canonical item 按自己的
`frame_type + generation + local_index` 进入 Java durable ingress。

`agent-stream.v4` 是独立的 multiplex 协议，主要事件包括：

- `public_frame_start`
- `public_frame_projection_item`
- `active_frame_snapshot`
- `frame_generation_reset`
- `public_frame_sealed`
- `public_frame_interrupted`
- `usage`
- `final`
- `error`

公开 item 先持久化、后投影到 SSE。Dialogue、Dossier、Quality 三个区域可独立更新，
但在唯一 `final` 提交前都只是 provisional；前端不得据此提前开放阶段动作。
`agent-stream.v3` 不扩充 V4 Frame payload 或 reset 语义。

## 7. Java 确定性合并

只有三个 Frame 都以 exact current generation sealed，Java Assembler 才能运行：

- 当前可见动作由上一持久 phase 派生；
- 正式案情增量只来自 Dossier；
- 六项分数只来自 Quality，总分由 Java 求和；
- Quality 缺口与 sealed Dossier matrix 做 fact-binding 对账；
- 公开回复由 Dialogue 候选、服务端动作和授权问题槽组合；
- `ready_for_next_step`、下一 phase、备注与 handoff 状态由 Java 规则派生；
- Java 生成唯一 `IntakeTurnProposal`，Finalizer 仍是唯一正式写入者。

阈值为 85 分，但“分数达到阈值”本身不能覆盖仍存在的阻塞缺口。身份、角色、来源、
matrix revision、hash、epoch、fence 或 command authority 不一致时失败关闭。

## 8. 失败、重放与恢复

- 每个 item、Frame seal、assembly 和 final 都有稳定 ID/hash。
- 相同 command/hash 重放采用已持久化结果，不重新调用 Provider。
- 单路可重试失败只 replacement 该 Frame；sealed sibling 保持不变。
- reset 以旧/new frame ID 和相邻 generation 显式表示，前端清除被替换 generation 的
  provisional projection。
- 未形成正式提交的失败以 `FAILED_UNCOMMITTED` 收敛，不能伪造成业务成功。
- Python 技术完成不等于业务完成；只有 Java durable final authority 和 Formal
  Finalizer 成功才可推进案件。
- 迟到事件、旧 generation、错误 local index、跨 actor/case/epoch 或 hash 漂移一律
  拒绝。

## 9. 模型与 Prompt 约束

当前三路统一使用 `qwen3.8-flash`，默认 `enable_thinking=false`，且由 LiteLLM 与
Python 设置共同固定 strict JSON Schema。Prompt 通过三路独立 profile 管理：

- 只要求模型生成它拥有的业务语义；
- 服务端已知值通过 request-bound Schema 收窄，不让模型重复猜测；
- 不把隐藏推理写入日志、stream、checkpoint 或正式结果；
- 不以第二模型、正则或关键词覆盖已经通过 Schema 和来源绑定的模型语义；
- Schema/来源错误触发有界局部重生成，不能切换模型或降级为自由 JSON。

## 10. 兼容与发布边界

- Java Flyway 当前上限为 `V094`；Graph migration 当前上限为 `G017`。
- V3/V4 合同、历史 checkpoint、旧 matrix hash 和 replay fixture 均需保留，不能因
  新 UAT 成功而删除。
- 目标 E2E lane 默认关闭；隔离 UAT 成功不等于默认生产开关已启用。
- 核心中间件版本变更必须单独授权、逐级迁移并保留恢复证据；本文不授权升级 Temporal
  或其他核心组件。
- 发布验收以[生产验证清单](../acceptance/temporal-first-agent-platform-verification-checklist.md)
  、[Canonical 回归夹具](../acceptance/canonical-full-chain-uat-fixture.md)和
  [当前 UAT 基线](../release/current-uat-baseline.md)为准。

## 11. 实现入口

- Python 父图与子图：`python-agent-service/app/graphs/intake/parallel_graph.py`
- Python 输入装配：`python-agent-service/app/graph_runtime/intake_parallel_context.py`
- Python bundle：`python-agent-service/app/graph_runtime/intake_parallel_bundle.py`
- Java staging：`IntakeParallelFrameStagingPort`
- Java Assembler：`IntakeParallelFrameAssembler`
- Java stream contract：`AgentStreamEventV4`
- 前端投影：`frontend/src/views/disputes/IntakeRoomView.vue`
