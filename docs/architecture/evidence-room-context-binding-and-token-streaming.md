# 证据室上下文、来源绑定与流式架构

- 状态：当前实现基线
- 更新：2026-09-04
- 代码基线：`main@10526e58b954498f69bae00ea709f6f9e4981971`
- 适用范围：证据室开场、材料核验、文字补充、重放和双方完成举证

本文替代旧的“V2 冻结设计/待切换”表述。当前 Evidence 链路已经使用 V2 业务上下文，
并通过 V3 结果与公开流合同进入目标 E2E Graph；Java 仍是证据和案件状态的唯一正式
写入者。

## 1. 当前版本身份

| 绑定 | 当前值 |
| --- | --- |
| Target Graph key | `all-rooms.target-e2e.v2` |
| Target Graph version | `target-e2e-graph.2026-08-18.3` |
| Target checkpoint | `target-e2e-checkpoint.v2` |
| Evidence cognitive graph | `evidence.v2.0.0` |
| Evidence state | `evidence-graph-state.v2` |
| Model context | `evidence_room_context.v2` |
| Provider stream object | `evidence_turn_stream.v3` |
| Committed frame authority | `evidence-turn-frame.v3` |
| Evidence result | `evidence-turn-result.v3` |
| Target Java proposal payload | `target-e2e-evidence-turn-proposal.v2` |
| Cross-service public stream | `agent-stream.v3` |
| Source catalog | `evidence_source_unit_catalog.v2` |
| 模型 | `qwen3.8-flash`，thinking 关闭，strict JSON Schema |

文件名或类名中的 `V2` 是兼容的实现名称，不代表线上结果仍是
`evidence-turn-result.v2`。接收端必须以记录中的 schema/version 字段为准。

## 2. 权威边界

```text
Java 授权案件、actor、room epoch、附件与冻结事实矩阵
  -> Python 构造 EvidenceRoomContext V2
  -> 仅加载已授权文本/图片来源
  -> 一次受治理的结构化模型调用
  -> 按顺序产生公开 Frame
  -> Python 物化 frame ID/hash 与 Evidence result V3
  -> Java 验证、持久化并正式提交证据投影
  -> Temporal 处理双方完成、Timer 与后续阶段
```

- 模型负责材料与事实关系、风险、局限、补证建议和面向当事人的说明。
- Java 负责身份、可见性、附件归属、哈希、ID、顺序、幂等、epoch/fence 和状态机。
- Python 不写 Domain DB，不推进正式阶段，不把未加载的材料描述为“已看见”。
- 公开流是 provisional；只有 Java Finalizer 接受的结果才能进入正式证据账本。
- 前端不根据模型措辞推断“举证完成”或跨方可见性。

## 3. Turn 模式

| 正式输入 | 模式 | 模型调用 | 允许的主输出 |
| --- | --- | ---: | --- |
| 当前 actor 首次进入 room epoch | `ROOM_OPENING` | 1 | 欢迎、案件导向、2–3 项证据问询、readiness |
| 当前 actor 提交附件 | `MATERIAL_REVIEW` | 1 | 接收说明、observation、逐附件 assessment、最多 3 项补证、readiness |
| 当前 actor 提交纯文字 | `TEXT_FOLLOWUP` | 1 | 文字回应、最多 3 项追问、readiness |
| 重新进入且已有正式结果 | `REENTRY_REPLAY` | 0 | 只重放已提交帧 |
| 点击完成举证 | 状态转换 | 0 | Java/Temporal 校验并推进 |

解析/OCR 和图片加载是模型调用前的独立授权步骤，不增加第二次 Evidence 语义调用。

## 4. 有序上下文

`evidence_room_context.v2` 的物理顺序固定：

1. `context_header`：matrix/state revision、room epoch 和覆盖状态；
2. `turn_contract`：模式、目标、允许帧、数量和顺序；
3. `authority_scope`：case、room、actor、当前事件和可见附件；
4. `frozen_case_matrix`：接待室正式冻结的事实与双方立场；
5. `current_evidence_batch`：当前附件元数据、解析与实际视觉加载状态；
6. `source_unit_catalog`：本轮唯一可引用的文本或像素来源单元；
7. `accepted_evidence_graph`：既有正式 observation 与 assessment；
8. `remaining_verification_requirements`：尚未覆盖的事实和冲突；
9. `private_actor_memory`：当前 actor 的最小私有窗口；
10. `output_contract`：精确帧序、类型和大小边界。

Evidence 上下文由 Java 已授权 envelope 投影而来，不重新读取或扩大 actor 可见范围。
跨方私有原文、凭证、内部能力、fence 和未授权附件不会被放入 Provider 内容。

## 5. Source Unit

一个物理来源区间只创建一个 Source Unit，ID 不包含 fact ID。同一 Source Unit 可以绑定
多个已授权事实，避免为每个 fact 复制相同 source span。

当前来源包括：

- 冻结 `parsed_text` 经稳定段落切分生成的文本单元；
- 通过 MIME、大小、归属和 SHA-256 校验后实际加载的图片像素单元。

若图片只存在元数据但像素未加载，模型不能声称检查了图像内容。若没有任何文本或视觉
Source Unit，request-bound Schema 会移除 `EVIDENCE_OBSERVATION` 能力，但仍允许模型
解释无法核验的局限。来源最多 64 个单元，单文本单元最多 12,000 字符。

Observation 只允许三种确定性：

- 已绑定：明确关联一个或多个允许的 fact；
- 无关：`UNRELATED`，不得携带 fact binding；
- 含糊：`AMBIGUOUS`，只列候选 fact，不伪造确定关系。

## 6. Provider 输出与帧序

Provider 根对象为：

```json
{
  "schema_version": "evidence_turn_stream.v3",
  "lead_public_text": "首帧公开文本",
  "frames": [
    {"header": {"frame_sequence": 2, "frame_type": "..."}, "public_text": "..."}
  ]
}
```

首帧 header 由状态机确定，模型只生成 `lead_public_text`，因此用户可在复杂语义头生成
前看到首包。后续帧必须先完成 header，再公开对应 `public_text`。

当前帧类型：

- `ROOM_WELCOME`
- `OPENING_ORIENTATION`
- `MATERIAL_RECEIPT`
- `TEXT_FOLLOWUP_REPLY`
- `EVIDENCE_OBSERVATION`
- `EVIDENCE_ASSESSMENT`
- `EVIDENCE_REQUEST`
- `ROOM_READINESS`

模式帧序：

```text
ROOM_OPENING:
  ROOM_WELCOME -> OPENING_ORIENTATION -> EVIDENCE_REQUEST(2..3) -> ROOM_READINESS

MATERIAL_REVIEW:
  MATERIAL_RECEIPT -> OBSERVATION* -> ASSESSMENT(每附件恰好一个)
  -> EVIDENCE_REQUEST(0..3) -> ROOM_READINESS

TEXT_FOLLOWUP:
  TEXT_FOLLOWUP_REPLY -> EVIDENCE_REQUEST(0..3) -> ROOM_READINESS
```

无来源单元的材料核验路径省略 `OBSERVATION`。未知帧、越序、重复 sequence、超出数量
或与模式不符的帧失败关闭。

## 7. 流式与正式结果

Provider 的公开字符串 delta 可即时投影，但不逐 token 写数据库。完整帧结束后才一次性
持久化：

- `frame_id` 和 `frame_sequence`；
- 完整 header 及 `header_sha256`；
- 完整 public text、长度及 `public_text_sha256`；
- `frame_sha256`。

最终 `evidence-turn-result.v3` 包含有序 `frame_manifest`、
`frame_manifest_sha256`、公开话术、引用证据 ID、observation graph、逐附件
assessment、补证请求和 room readiness。`agent-stream.v3` 负责跨服务持久流和
断线重放；终态之外的文本不能写入正式房间消息。

刷新只重放已持久化内容。相同 command/hash 返回相同结果，不再次调用模型；中断或
aborted attempt 不得与新 attempt 拼接。

## 8. Java 接收与正式化

Java 接收端重新验证：

- schema/frame authority 版本；
- frame sequence、类型、顺序、数量与 manifest hash；
- evidence ID、Source Unit、observation slot 和 fact ID 是否属于当前授权；
- 每个附件恰好一个 assessment；
- assessment 只能引用同附件且已接受的 observation；
- 图片/解析内容 hash、actor scope、room epoch、fence 和 command authority；
- proposal/result hash、幂等与 projection revision。

模型不得生成正式 evidence ID、业务 sequence、room completion、责任认定或执行动作。
Java 可从已接受 observation 确定性生成 ID、边、投影与公开引用，但不能用关键词、
文本相似度或第二模型改写已经通过来源绑定的业务判断。

## 9. 前端

Evidence 页面只在下列版本完全匹配时消费目标投影：

- Graph `target-e2e-graph.2026-08-18.3`；
- checkpoint `target-e2e-checkpoint.v2`；
- state `evidence-graph-state.v2`；
- assessment `evidence-turn-result.v3`。

页面按当前 actor 过滤目录、消息和 SSE audience；展示 observation、评分说明、局限、
补证请求和 readiness，不显示 raw JSON、隐藏推理或未授权另一方私有材料。上传图片只有
在服务端资产清单与像素 hash 通过后才可标记为视觉核验。

## 10. 失败与兼容

- 任何版本、来源、hash、顺序、数量、actor、epoch 或 fence 漂移都失败关闭。
- Provider 429、超时或 Schema 错误只按统一预算重试，不回退自由文本或换模型。
- 正式提交失败只重试 Java Finalizer，不重跑已经完成且可精确重放的模型调用。
- 历史 `evidence-turn-result.v2`、旧 Graph 版本和旧 fixture 仍可作为兼容/回放
  资产保留；新写入只能使用当前绑定。
- 目标 E2E lane 默认关闭；UAT 成功不能替代生产开关、容量、安全和恢复门禁。
- 核心组件升级必须另行授权，本文不授权修改 Temporal、PostgreSQL 或其他平台版本。

## 11. 实现入口

- 上下文：`apps/agent-runtime/app/harness/evidence_room_context_v2.py`
- Prompt：`apps/agent-runtime/app/agents/prompts/evidence_clerk/evidence_turn_v2.md`
- 模型工作流：`apps/agent-runtime/app/agents/evidence_clerk/v2_workflow.py`
- 结果合同：`apps/agent-runtime/app/agents/evidence_clerk/v2_contracts.py`
- Graph executor：`apps/agent-runtime/app/graph_runtime/evidence_turn_executor.py`
- Java transport：`TargetEvidenceTurnResultV2`
- Java 正式化：`EvidenceAgentTurnService`、`JdbcTargetEvidenceTerminalActivities`
- 前端：`apps/web/src/views/disputes/EvidenceRoomView.vue`
