# Intake 接待官三路 Frame 并行 Graph 重构计划

## 状态

```text
plan_status: IMPLEMENTED_FOCUSED_VERIFIED
implementation_status: R5_THREE_NODE_PARENT_FOCUSED_VERIFIED
runtime_change: SOURCE_COMPLETE_FRESH_ACTIVATION_REQUIRED
database_change: IMPLEMENTED_JAVA_V081_TO_V088_AND_GRAPH_G011_TO_G013
uat_status: PENDING_FRESH_ACTIVATION
target_model: qwen3.7-max-2026-06-08
provider_output_mode: STRICT_JSON_SCHEMA
thinking: DISABLED
target_execution_profile: PARALLEL_FRAMES_V1
legacy_execution_profile: MONOLITHIC_V3
parallel_stream_protocol: agent-stream.v4
```

截至 2026-08-27，R1–R4 的契约、三路独立 Graph/checkpoint、Java V4 staging/assembly/finalization、前端三槽 provisional projection 与局部 reset 已完成。Quality Provider 只生成 Schema 固定的六个评分槽和独立 `gap_candidates`；服务端按固定维度顺序物化成既有 score/gap typed item 后送入 Java。Java 在正式状态派生前用 sealed Dossier matrix 对缺口 fact binding 做跨 Frame 对账，且不改写六项分数。

2026-08-26 的拓扑校正把 Python 顶层明确实现为一个仅含 `dialogue_frame`、`dossier_frame`、`quality_frame` 三个并列 Node 的父 `StateGraph`。三个 Node 分别调用既有的 lane-local child graph，继续使用独立 Prompt、Schema、checkpoint namespace、generation/reset 和 Provider 调用；Python 父图没有 join/assembler/Proposal 节点。每路 canonical item 与 sealed 事件仍在产生时直接进入 Java durable ingress，父图的 END barrier 只决定本次技术调用何时返回，不阻塞任一路首包或 checkpoint。单路重试时另外两个 Node 只执行无副作用 skip，不调用 sibling 模型或 child graph。

首轮 fresh activation 已证明三个父图 Node 会同时进入真实 Provider，但 Dossier 分类枚举和 Quality Provider-invisible root validator 造成首代及单路重生成重复失败。2026-08-27 已把这两路改为短 Provider draft + 服务端确定性物化，相关 Provider draft/materialization、三节点 Graph、Prompt、API stream 和 parallel contract 聚焦门通过 84/84；新的 fresh activation、正式矩阵写入和浏览器全链路 UAT仍待执行，因此当前状态不构成运行态验收或发布结论。

三节点父 Graph 校正后的完整 `test_parallel_graph.py` 通过 28/28，包含精确三 sibling 拓扑、父完成前独立首包、独立 child checkpoint、单路 generation reset、单路失败隔离、只重跑一个 active lane 以及外部取消传播；`py_compile` 与 `git diff --check` 同时通过。该结果只证明 Python 技术拓扑，不替代 fresh activation 下的 Java exact-three assembly、正式 Intake 事务和冻结矩阵 UAT。

本文定义最终交付所需的完整实施边界、协议、切分顺序和验收门，不在规划阶段修改业务代码、数据库、运行服务或现有案件。当前单体接待官仍是生产真值。实施切片只用于控制提交和验证边界，不代表功能降级、分期协议或临时子集；`PARALLEL_FRAMES_V1` 只有在本文全部契约和验收门同时完成后才能启用。

本文承接但不覆盖当前架构说明：

- [Intake Room Context And Streaming](../docs/architecture/intake-room-context-and-streaming.md)
- [Phase 4 Intake Pilot Execution Plan](./phase-4-intake-pilot-execution.md)

## 1. 决策摘要

接待官由一个“大上下文、大 Schema、大输出”的模型节点，重构为同一 Graph command 内的三个并行模型节点：

1. `DIALOGUE_FRAME`：生成可独立校验的公开语义 segment proposal；仅在备注等待态输出最小 `remark_disposition`，最终动作、slot trace、问题与公开回复由服务端组合。
2. `DOSSIER_FRAME`：生成可独立校验的案情 typed patch proposal 与最终结构化增量。
3. `QUALITY_FRAME`：生成可独立校验的评分/gap typed metric proposal 与最终六项评分和受约束缺口候选。

三个节点使用完全相同、一次冻结的业务上下文快照，只改变各自的 System 指令、输出 Schema 和输出预算。三路模型均固定为 `qwen3.7-max-2026-06-08`、strict JSON Schema、thinking 关闭；本方案不混用小模型，不引入模型自动降级。

三路输出不能各自改写正式案件状态。Provider 调用前，Java 必须原子 admission exact-three Frame manifest 并返回 durable ack；三个 Node 随后把各自 validator 返回的 canonical typed projection item 写入同一个 attempt-scoped multiplex ingress。Python 父运行器只为当前 transport session 分配连接内顺序，Java 为每个公开 item 原子持久化 per-Frame `next_local_index`、durable event/outbox 和 cursor；重放只认 Java durable cursor、per-Frame progress 与 hash。Java 收齐三个 sealed Frame 后执行唯一确定性 Assembler/Reducer：

- 当前轮用户可见动作只取上一轮已持久化阶段；
- 本轮案情增量只来自 `DOSSIER_FRAME`；
- 六项分数只来自 `QUALITY_FRAME`，总分由服务端求和；
- `ready_for_next_step`、下一轮阶段、备注状态和交接状态由服务端规则派生；
- Java 自己从三个 Frame 生成现有 `IntakeTurnProposal`，并继续作为正式领域事实的唯一写入者；Python 不再组装或二次提交 Proposal。

前端不再等待整份单体结果。Java admission outbox 使三路 Frame 同时进入“生成中”；每个完整、独立校验并已 durable 的 Dialogue segment、Dossier patch、Quality metric 到达后分别更新聊天区、案情卡和完善度区域，无需等待该 Frame 或 Provider 完成。这些更新在 Java 唯一终态事务前都属于 durable provisional projection，不能提前开放提交或阶段跳转。

## 2. 为什么采用三路并行

### 2.1 当前主要问题

现有单体输出要求模型在一个 JSON 根对象中同时处理：

- 公开自然语言回复；
- 当前来源的事实增量；
- 累计案情展示；
- 双方观点与历史态度承接；
- 六项评分；
- 缺口与下一问题；
- 当前动作、下一阶段、备注和交接状态。

这些字段跨越“上一轮权威、本轮增量、冻结矩阵、展示投影、下一轮状态”五种不同时间和权威层。模型需要重复表达同一语义，容易出现以下错误：

- 当前轮 `NOT_ADDRESSED` 与累计历史态度互相冲突；
- 六项已达阈值，但模型仍复用旧 `NOT_READY` 或旧问题；
- 自由文本缺口标签被错误当成阻塞事实；
- `total_score` 与六项求和形成双重权威；
- 当前可见动作被本轮新评分改写，而不是遵循上一持久阶段；
- 大 JSON 任一字段失败导致整次生成重试。

这不是单纯的随机“模型抖动”。根因是一个模型输出同时承担了过多互相依赖、时间语义不同的职责。采样抖动会放大问题，但权威边界和重复表达才是结构性原因。

### 2.2 预期收益

- 每次输出显著缩短，strict Schema 更容易满足。
- 一个 Frame 失败时只重试该 Frame，不丢弃另外两路已完成结果。
- 首个可见内容不再等待全量案情和评分完成。
- 分数、动作、阶段不再由公开回复的措辞影响。
- 每个语义字段只有一个模型生产者或一个服务端生产者。
- 失败可定位到具体 Frame、Schema、模型调用和 Java Assembler 规则。

### 2.3 明确代价

- 同一业务上下文会发送三次，输入 token 和 Provider 调用数上升。
- Provider 并发限流、连接池和全局吞吐需要单独控制。
- 最终完成时间由最慢 Frame 决定，不保证必然是原来的三分之一。
- 三路独立理解可能产生语义差异，必须由 Java 确定性 Assembler 和唯一字段所有权消除，而不能让模型互相覆盖。

因此本计划的成功指标不是“调用数更少”，而是“首屏更快、终态关键路径由串行和变为并行最大值、Schema 错误局部化，并保持正式状态唯一权威”。

## 3. 范围与非目标

### 3.1 范围

- Intake 接待官当前实质消息路径。
- Python LangGraph 的 fan-out、三个独立 Frame checkpoint、局部重试和单一 multiplex Java Frame ingress client。
- Qwen 3.7 strict JSON Schema Prompt/Profile 拆分。
- Java 对三个 sealed Frame 的持久化、合并、现有 `IntakeTurnProposal` 生成、回放与正式写入。
- 新的 `agent-stream.v4` 并行 Frame 投影；既有 `agent-stream.v3` 保持严格、只读兼容，不扩充其事件集或 payload。
- 前端聊天、案情、完善度三处并行渲染和终态校准。
- 新旧执行 Profile 的 epoch 级固定、灰度和回退。

### 3.2 非目标

- 不改 Evidence、Hearing、Review、Outcome Graph。
- 不在本重构中切换到更便宜或不同能力的模型。
- 不让 Python、模型或前端获得正式案件写权限。
- 不删除历史 checkpoint、旧事件槽 generation 或旧矩阵 hash 支持。
- 不用自然语言语义校验替代权限、角色、epoch、fence、hash 和幂等校验。
- 不在同一个 Intake epoch 中从单体 Profile 热切换到并行 Profile。
- 不以局部 hardcode、案件 ID、字段前缀猜测或放宽权限通过 UAT。

## 4. 实施前的选择性回退与保护边界

不存在可证明代表成功案件 `CASE_P9_6A8AC2C9_1` 的单一整仓 commit。禁止 `git reset --hard`、整树 checkout 或整提交机械 revert。实施前只能创建定向 restoration commit。

### 4.1 第一批定向恢复

把下列配置作为一个原子提交恢复到稳定的 Qwen 3.7 配置：

- `python-agent-service/app/config.py`
- `python-agent-service/tests/test_llm_settings.py`
- `.env.example`
- `deploy/litellm/config.yaml`
- `docker-compose.yml`
- `python-agent-service/tests/static/test_repository_contract.py`

固定目标：

```text
model = qwen3.7-max-2026-06-08
strict_json_schema = true
thinking = false
```

该提交只反向恢复 `a1626dbb` 与 `40049df0` 的模型/路由切换代码，不回退 `P0_ISSUES.md` 历史。模型和 Prompt 指纹变化后必须创建 fresh activation，禁止拿旧 Qwen 3.8 checkpoint 当作相同 Profile 回放。

### 4.2 必须保留的通用修复

| 机制 | 处理 | 原因 |
| --- | --- | --- |
| V080 事件槽 generation、supersedes、current authority 和 CAS | 保留，不可回退 | 已落库且负责失败 run 的安全恢复、回放与单写者约束 |
| `JdbcIntakeGraphBindingStore` 最新 attempt 精确证明 | 保留 | 防止较旧 matching attempt 错误授权 recovery |
| 历史/current 矩阵 hash 双读 | 保留 | 保障旧冻结矩阵可回放，且不丢失 omitted presence |
| server-only initiator role authority | 保留 | 身份必须来自冻结 authority，不能由模型或当前 actor 推断 |
| 六项评分求和为唯一总分 | 保留 | 禁止 Provider 独立 `total_score` 形成双权威 |
| optimistic revision 冲突映射 | 保留 | 维持并发/idempotency 的透明领域冲突 |
| previous phase 决定当前动作 | 保留并集中实现 | 这是已验证的阶段真值，不是 Prompt 偏好 |
| current delta 与历史 respondent attitude 分离 | 保留并重新归位 | 当前事实消息不得重新归因或抹除已有直接态度 |
| 完整 `FACT_`/`NEW_` key 输出要求 | 保留 | exact key 是矩阵 authority 的一部分 |

### 4.3 重写而不是裸回退的部分

- `5dc7c04a`、`498fd92d`、`83c28150` 中围绕单体十段输出重复出现的 Prompt、动态 Schema 和 Reducer 表达，迁移到三个 Frame 的单一字段所有权。
- `96af19dd` 的缺前缀唯一补全暂作 legacy replay 兼容；只有 exact request-bound key 契约覆盖新写入并完成旧数据回放策略后才删除。
- 当前 `python-agent-service/app/agents/hearing_flow.py` 与 `python-agent-service/tests/agents/test_hearing_flow_v2.py` 的未提交用户修改不属于本计划，任何 restoration 都不得触碰。

## 5. 权威与时间语义

### 5.1 核心公式

```text
current_action_binding = derive_action_binding(previous_persisted_phase)
current_dossier_delta  = dossier_frame(shared_context, current_message)
current_quality        = quality_frame(shared_context, current_message)
next_persisted_state   = java_deterministic_assembler(
                           previous_persisted_state,
                           current_dossier_delta,
                           current_quality
                         )
dialogue_candidate     = dialogue_frame(shared_context, current_action_binding)
public_reply           = java_deterministic_dialogue_composer(
                           dialogue_candidate.acknowledgement_text,
                           current_action_binding,
                           authorized_question_slots
                         )
```

`current_action_binding` 是服务端由 `previous_persisted_phase` 确定性派生的只读绑定，不是第二个业务权威。两者不一致时在调用模型前 fail closed。本轮评分不会改变本轮已经由上一持久状态决定的可见动作。本轮新结果只形成下一持久状态，并在下一轮成为动作权威。这与现有接待规则一致。

### 5.2 字段唯一所有权

| 字段/语义 | 唯一生产者 | 其他节点允许做什么 | 禁止行为 |
| --- | --- | --- | --- |
| 最终公开回复 | Java DialogueComposer（承接候选来自 Dialogue Frame，问题/动作来自 server binding） | Java Assembler附加协议元数据 | 模型自建问题文本；Dossier/Quality 重复输出 prose |
| 当前事实和角色本地观点增量 | Dossier Frame | Java Assembler规范化 key/binding | Quality 修改事实；Dialogue 写矩阵 |
| 累计展示卡片 | Java Assembler 基于 previous + Dossier delta | 前端只做展示 | 模型重复生产另一份累计真值 |
| 六项分数 | Quality Frame | Java Assembler求和、校验范围 | Dossier/Dialogue 输出分数 |
| 阻塞缺口候选 | Quality Frame | Java Assembler按封闭维度、当前来源与 Dossier 结果过滤 | 自由字符串直接决定 blocking |
| 总分 | Java Assembler | 前端显示 | Provider 输出 `total_score` |
| 当前可见动作 | previous persisted phase + Java Assembler | Dialogue 按已给定动作组织措辞 | 模型自行选择/回退动作 |
| 下一阶段/ready/remark/handoff | Java Assembler | Python 不派生正式状态 | 三个模型直接输出正式状态 |
| 正式案件状态 | Java + Domain PostgreSQL | 三个 Frame 只提交 staging/stream 数据 | 单个 Frame 完成即写正式案件事实 |

## 6. 一次冻结、三路复用的上下文契约

上下文分成服务端权威信封和 Provider 可见视图。`IntakeParallelContextEnvelopeV1` 在 fan-out 前生成一次，包含执行和权限绑定；服务端再从中确定性投影一个脱敏的 `IntakeModelContextViewV1`。三个模型节点只读复用完全相同的 Model View，不接触 server-only capability。

### 6.1 Server-only Context Envelope

```json
{
  "contract_version": "intake.parallel-context-envelope.v1",
  "case_ref": {
    "tenant_id": "...",
    "case_id": "...",
    "thread_id": "...",
    "room_id": "...",
    "room_epoch": 1,
    "fence_token": "..."
  },
  "source_event": {
    "message_id": "...",
    "logical_sequence": 1,
    "actor_id": "...",
    "actor_role": "USER",
    "payload_sha256": "..."
  },
  "authority": {
    "initiator_role": "USER",
    "respondent_role": "MERCHANT",
    "authority_snapshot_ref": "...",
    "authority_snapshot_sha256": "..."
  },
  "previous_state_ref": "...",
  "model_context_view_sha256": "...",
  "context_envelope_sha256": "..."
}
```

tenant/case/thread/room 标识、actor id、fence、内部 authority ref 和写入 capability 只存在于该信封和 Java/Python服务端校验链，不进入 Provider 消息，也不允许模型回写。

### 6.2 Provider-visible Model Context View

```json
{
  "contract_version": "intake.model-context-view.v1",
  "source_capacity": {
    "business_role": "USER",
    "litigation_capacity": "INITIATOR",
    "writable_partition": "INITIATOR_ONLY"
  },
  "previous_state": {
    "revision": 8,
    "persisted_phase": "NOT_READY",
    "quality": {},
    "dossier_projection": {}
  },
  "current_action_binding": {
    "action": "ASK_SUBSTANTIVE",
    "derived_from_phase": "NOT_READY",
    "phase_source_sha256": "..."
  },
  "authorized_question_slots": [
    {
      "question_id": "Q_...",
      "target_capacity": "INITIATOR",
      "source": "PREVIOUS_PERSISTED_STATE",
      "canonical_text": "具体、可回答的中文问题",
      "canonical_text_sha256": "..."
    }
  ],
  "frozen_case_matrix": {
    "version": 3,
    "sha256": "...",
    "payload": {}
  },
  "recent_dialogue_messages": [],
  "current_user_message": {},
  "model_context_view_sha256": "..."
}
```

### 6.3 上下文规则

- 初次上线保持与当前单体节点相同的脱敏业务内容和顺序，只拆输出，不同时做激进裁剪，以便把收益和回归归因到输出拆分。
- `previous_state.persisted_phase` 是唯一阶段权威；`current_action_binding` 必须由服务端从它派生并在 fan-out 前校验，不允许外部独立提供。
- `authorized_question_slots` 是 Dialogue 唯一可引用的问题集合；未知 id、错误 target capacity 或文本 hash 漂移均拒绝。
- `current_user_message` 是本轮 current-source delta 的唯一来源。
- `frozen_case_matrix` 为只读引用集合；任何 fact id 必须从其 exact key 或本轮合法 `NEW_` namespace 中选择。
- 三个模型请求必须携带完全相同的 `model_context_view_sha256`；三个 Frame manifest 还必须由服务端绑定同一 `context_envelope_sha256`。任一路不一致时 fan-in fail closed。
- 三路共享的是同一份不可变业务事实视图和 `model_context_view_sha256`，不是同一份完整指令上文。Prompt Composer 必须把公共事实与规则分层：公共层只提供来源/只读边界；Frame 专属层只提供本路职责、字段、Schema 和预算。
- 任一 Frame 的 System/Human 指令不得携带其他 Frame 的评分细则、展示措辞、字段清单或输出示例；这种跨路规则污染属于契约错误，而不是模型自由裁量。
- actor business role 与诉讼容量分离；USER/MERCHANT 不自动等价于 initiator/respondent。

## 7. 三个小 Frame 的严格 Schema

### 7.1 Dialogue Frame

职责：生成本轮公开回复，遵循服务端派生的 `current_action_binding` 和最多两个 `authorized_question_slots`，但不重复输出这些服务端权威。

```json
{
  "public_projection_items": [
    {
      "schema_version": "intake.dialogue-public-segment-proposal.v1",
      "provider_slot_id": "DSEG_01",
      "segment_kind": "ACKNOWLEDGEMENT",
      "candidate_text": "仅陈述确认和承接内容的中文完整短句"
    }
  ],
  "frame_type": "DIALOGUE_FRAME",
  "schema_version": "intake.dialogue-frame.v2",
  "dialogue": {
    "remark_disposition": null
  }
}
```

限制：

- 不输出分数、总分、ready、phase、remark、handoff、矩阵或累计卡片。
- 不输出 `action_binding`、phase hash、language、`public_projection_slots` 或 `question_binding_ids`；Java 从 previous phase、accepted item trace 和授权问题槽派生这些值。
- 不自行创建下一问题；需要追问时，问题正文仍只来自输入中的 `authorized_question_slots`，Provider 不输出问题 id 或文本。
- `public_projection_items` 必须是 Provider JSON 物理首字段；增量投影器只在一个完整 item object 闭合后交给 request-bound prefix validator，不输出字符串 prefix 或半对象。
- 每个 `DialoguePublicSegmentProposalV1` 限长、禁止 `?`/`？`，只能是封闭 `segment_kind` 对应的完整句。validator 校验 actor/action/question authority、重复/顺序、禁用承诺/新请求/未知动作和累计预算，返回 canonical `PublicFrameProjectionItemV1` 后才可公开。
- Provider 不输出最终 `room_utterance`，也不输出问题文本。服务端 `DialogueComposer` 以已接受 canonical segment 的 `public_text + authorized_question_slots.canonical_text` 确定性构造最终公开回复，并记录 segment/action/question binding hash。
- `remark_disposition` 在非 `WAITING_FOR_REMARK` 阶段必须为 `null`；该阶段只能为 `REMARK` 或 `NO_REMARK`，Java 据此映射 `ACK_REMARK`/`ACK_NO_REMARK`。它不拥有最终 action。
- Final Dialogue Frame 不再重复输出 slot 列表；Java 以 durable accepted item trace 与 `next_local_index` 证明每个 Provider slot exact-once、同序。不得再用一个独立 `acknowledgement_text` 重写已公开内容。
- 当 current action 为邀请备注或确认无备注时，不得回退到实质追问。
- 初始输出预算建议不超过 1,024 tokens，最终以基线测量校准。

### 7.2 Dossier Frame

职责：抽取当前来源的事实增量和角色本地观点，并给 Java Assembler 构造累计案情投影所需的结构化材料。

```json
{
  "public_projection_items": [
    {
      "source_row": {
        "fact_key": "FACT_01",
        "fact_target": "商品使用状态",
        "stance": "CONFIRM",
        "position_summary": "当前来源事实摘要",
        "asserted_value": "当前来源事实"
      }
    }
  ]
}
```

限制：

- 只写当前 actor 有权提供的本方字段；对方已有观点由服务端从上一持久矩阵承接。
- 当前消息未表达回应时，`respondent_claim=null`；这不允许抹掉历史 grounded attitude。
- 不输出任何评分、缺口、ready、动作、remark 或 handoff。
- fact key 必须逐字复制 authority 中的完整 key；新事实必须使用服务端提供的 namespace。
- Dossier 可见面只允许 `CURRENT_FACT + case_story.one_sentence_summary`；该字段复用现有持久化结构，不引入新的卷宗成员。
- 每条 current-source 事实只生成一次完整 `source_row`。`candidate_value`、`provider_slot_id`、`public_projection_slots`、`matrix_patch.fact_rows` 和 `summary_source_fact_keys` 都由服务端从该唯一行确定性派生，不再要求模型跨字段复制同一事实。
- Provider 不生成 `category`、`materiality` 或 source scope。既有 `FACT_` 行由服务端从冻结矩阵恢复原分类/目标/重要性；当前 `NEW_` 行使用一个保守、确定性的服务端分类，再进入既有稳定 `IntakeDossierFrameV3`。因此 Provider 枚举漂移不会改变 Java 输入 Schema。
- 每个 command 的 Provider-visible Dossier Schema 都由冻结矩阵和 authenticated capacity 请求级收窄：已有 key 是 exact `FACT_` 枚举，新 key 只能匹配该 event 的 `NEW_<namespace>_` 前缀；发起方 Schema 将 `respondent_claim` 固定为 `null`。Prompt 只解释同一规则，Python terminal validator 与 Java assembler 继续作为纵深拒绝边界。
- Python 每闭合一个 item 即以 `source_row.fact_key` 作为 technical projection identity，并以 `source_row.position_summary` 流式公开；Java Assembler 按 accepted item 原顺序构造现有 `case_fact_matrix.delta.v2` 与 `case_story.one_sentence_summary`，最终 `IntakeTurnProposal` 结构不变。
- Provider 仍只写本轮 current-source rows；Java 从 command-bound immutable previous dossier 读取 Java-owned frozen matrix，先按原 formal row 顺序补齐每个未更新父事实的 `PREVIOUS_MATRIX` carry，再追加当前 `NEW_` rows。现有 initiator/respondent freezer 仍是正式矩阵写入边界，缺父行、rebound、跨角色 claim 或 namespace 漂移全部 fail closed。
- respondent 的纯事实后续轮若没有新 claim，Java carry 保留上一版 grounded respondent position、`respondent_direct` 与 `claim_conflict`，不得把历史陈述重新归因到当前 message，也不得用 `null` 清空历史权威。
- Dossier Provider Schema 只暴露 current-source fact key、目标、stance 和短文本；source scope、分类、materiality 与历史 carry 都是服务端权威。根级只保留 fact-key 唯一性和聚合长度纵深校验。
- 初始输出预算建议不超过 2,048 tokens，后续按数据分布收敛。

### 7.3 Quality Frame

职责：独立评估同一个冻结 Model View 中的“上一持久状态 + 当前原始消息”，为下一轮状态提供六项评分候选和缺口候选。它不读取 Dossier Frame，也不决定本轮动作。Dossier 与 Quality 是对同一输入的并行解释，不形成先后依赖。

```json
{
  "public_projection_items": [
    {
      "projection_kind": "DIMENSION_SCORE",
      "dimension": "REFERENCES",
      "candidate_score": 0
    },
    {"projection_kind":"DIMENSION_SCORE","dimension":"EVENT_STORY","candidate_score":0},
    {"projection_kind":"DIMENSION_SCORE","dimension":"PARTY_POSITIONS","candidate_score":0},
    {"projection_kind":"DIMENSION_SCORE","dimension":"REQUESTED_RESOLUTION","candidate_score":0},
    {"projection_kind":"DIMENSION_SCORE","dimension":"RISK_AND_CONFLICTS","candidate_score":0},
    {"projection_kind":"DIMENSION_SCORE","dimension":"NEXT_ACTION_CLARITY","candidate_score":0}
  ],
  "gap_candidates": [
    {
      "dimension": "EVENT_STORY",
      "question": "具体、可回答的中文问题？",
      "linked_fact_keys": []
    }
  ],
  "quality": {"assessment_reasoning": "简短中文说明"}
}
```

限制：

- 严禁输出 `total_score`；Java Assembler 只对六项求和。
- `dimension` 使用封闭 Enum；禁止任意机器字段名直接成为 blocking gap。
- gap 必须是当前角色可回答、当前上下文尚未覆盖且能绑定到合法来源或 fact key 的具体缺口。
- 六项分数是基于 Model View 的唯一模型评分候选。Java Assembler 只做范围/完整性校验和求和，不依据 Dossier Frame 重算或改写分项；如果 Dossier binding 与 Quality 声称的覆盖情况发生不可解释冲突，整次 Java assembly fail closed。Dossier 仅可证明某个 gap 已被覆盖，从而删除该 gap，不得提高或降低六项分数。
- Provider Schema 用六元素 typed tuple 固定 score 的类型、顺序和范围；Provider 无法在第七项再生成 score。`gap_candidates` 是独立数组，不与 score prefix 交错。
- 服务端丢弃满分维度候选，按候选规范内容确定性处理同维重复，再严格按六维固定顺序物化成既有 `BLOCKING_GAP`。Provider 数组顺序不参与 result/proposal hash。
- Final Quality Frame 仍使用既有 `IntakeQualityFrameV2`，其 score/gap typed trace 与已送入 Java 的 canonical item exact reconciliation；它不派生 total/ready/phase，UI 始终标记为 provisional。
- 不输出 ready、conversation action、phase、remark status、handoff 或 admission。
- 初始输出预算建议不超过 1,024 tokens。

### 7.4 三类 Frame 共用的 typed projection item 契约

三个 Provider Schema 都把 `public_projection_items` 放在物理首字段。增量 JSON projector 只产生“完整数组 item 已闭合”内部事件；随后必须同步调用对应 request-bound prefix validator：

```text
validate_public_projection_prefix(
  prior_accepted,
  candidate,
  frozen_model_view,
  actor/action/question/source/fact authority,
  projection_registry_version
) -> PublicFrameProjectionItemV1
```

canonical return 至少包含：

```text
frame_type / generation
provider_slot_id
canonical_item_id
projection_kind / projection_path_id / value_kind
canonical_value_json or public_text
next_local_index
item_sha256
authority_binding_sha256
```

只有 validator return 可送往 Java；raw Provider item、半 JSON、reasoning、未知 path 和 Provider 自带 canonical id/hash/revision 永不公开。validator 每次重验 prior prefix，执行 source order、duplicate、count/byte/item budget 和 authority lineage；任一 candidate 失败时整个 Frame generation interrupted，不发布该 candidate。

完整 Frame Schema 通过后执行 terminal reconciliation：Dialogue 直接以 accepted canonical item trace 为唯一 slot authority；Dossier 的 Provider row 在公开前先物化成既有稳定 row，最终必须与该 V3 trace 同序同值；Quality 的六项 score prefix 必须 exact reconciliation，规范化 gap candidates 由服务端按固定规则追加成旧 gap trace。除这些显式登记的确定性物化，以及 action/question/notice composer 外，terminal 不得静默添加模型语义。

## 8. 目标 Graph 结构

### 8.1 DAG

```text
Parent coordinator graph
------------------------
START -> authorize_and_load -> import_snapshot_once_or_apply_event
      -> route_turn (authoritative source_type discriminator)
           | INITIAL_FORM      -> fresh_form_opening_node
           | RESPONDENT_OPENING-> respondent_opening_node
           | FORMAL_EVENT      -> deterministic_transition_node
           | ROOM_MESSAGE      -> freeze_parallel_context
      -> [ROOM_MESSAGE only] reserve_three_provider_permits_as_one_group
      -> [ROOM_MESSAGE only] java_batch_admit_parallel_frame_set
      -> [ROOM_MESSAGE only] dispatch_three_frame_children
      -> wait_and_consume_frame_completion
            | retryable one-frame failure -> dispatch_that_frame(generation + 1)
            | fatal/exhausted/deadline    -> send_frame_failure_to_java -> fail
            | not all sealed              -> wait_and_consume_frame_completion
            | all three sealed            -> wait_for_java_terminal_receipt
      -> END

Independent child graph/checkpoint namespace (three instances)
------------------------------------------------------------
frame_child_start
  -> load_frozen_context_by_hash
  -> run_one_frame_lcel
       \-> first-byte metric / canonical typed projection items -> parent bounded merge -> Java multiplex ingress
  -> validate_one_frame
  -> write_child_terminal_checkpoint(SEALED | RETRYABLE_FAILED | FATAL_FAILED)
  -> submit_frame_terminal_to_java_idempotently
       \-> Java persists Frame slot; if exact three SEALED, Java assembles to READY
  -> notify_parent_coordinator_at_least_once
  -> frame_child_end

Java Frame ingress / assembly
-----------------------------
frame event -> Python parent assigns session-local transport sequence
            -> Java validates admission/session/generation/transport sequence/per-frame index
            -> atomically persists canonical projection item/progress/outbox -> frontend
frame sealed -> persist immutable Frame result + CAS current Frame slot
             -> lock attempt assembly
             -> exact three current slots SEALED?
                  no  -> return frame receipt
                  yes -> Java deterministic assembler
                       -> stage immutable IntakeTurnProposal artifact
                       -> emit durable technical RoomGraphResult / graph FINAL
                       -> AgentRun RESULT_READY
                       -> existing Target outer finalizer
                       -> one formal domain transaction + terminal receipt
```

`fresh_form_opening_node` 与 `respondent_opening_node` 各自使用既有开场语义对应的专属 Prompt/Schema，只负责首次进入房间的说明和首组提问；它们不进入 Dossier/Quality 三路，也不得写本轮并行评分增量。`ROOM_MESSAGE` 才是“接收参与方回答后整理回复”的三路并行路径。`FORMAL_EVENT` 只执行服务端确定性阶段转换。路由值只能来自可信 `IntakeTurnEvent.source_type`，不得让模型自行判断当前属于开场还是整理回复。

顶层使用 LangGraph 的一个 fan-out superstep，结构上只有三个并列业务 Node；每个 Node 内部是可独立调度、独立完成、独立持久化的 child graph。superstep 的父调用完成 barrier 不构成业务或流式 barrier：任一路无需等待另外两路即可向 Java 写 projection、usage、sealed 事件并完成自己的 terminal checkpoint。父图只形成技术执行摘要，不读取三个结果做语义拼装。

### 8.2 父图状态与三个独立子图 checkpoint

父 `StateGraph` 不让三个并行任务并发写同一个 state key：三个 Node 分别写 `dialogue_outcome`、`dossier_outcome`、`quality_outcome`，且这些 outcome 仅用于本次技术返回。持久化的父级调度、retry、Frame slot 和 Java receipt 权威由 Java admission/staging 保存；Python 不再维护第二份可变父 checkpoint，也不保存可供 Python 合并的 Frame Set或生成 Proposal：

```text
context_envelope_ref
context_envelope_sha256
model_context_view_sha256
provider_group_lease_id
java_frame_set_admission_receipt_ref
frame_slots[frame_type]: {
  selected_generation,
  next_generation,
  status,
  child_checkpoint_namespace,
  child_terminal_checkpoint_ref,
  java_frame_receipt_ref,
  attempts[],
  sealed_result_sha256
}
frame_retry_queue[]
completion_event_dedup[]
turn_deadline_at
java_assembly_status
java_terminal_receipt_ref
```

Java 必须先把 immutable dispatch manifest 作为 FrameSet admission 原子落盘，再允许父图启动任何 child。manifest 精确列出三个 frame type、generation、child namespace、context/model-view hash、admission receipt、provider lease 和 deadline。child saver 只能写自己的 namespace，不得推进其他 child 的恢复指针；恢复只能由 Java current slot/immutable result 选择需要运行的 lane，再读取该 lane 的 exact namespace，禁止从“最新 checkpoint”猜测父子关系。

每个 child graph 使用独立 checkpoint namespace：

```text
intake/{logical_run_id}/{attempt_id}/frames/DIALOGUE_FRAME/g{generation}
intake/{logical_run_id}/{attempt_id}/frames/DOSSIER_FRAME/g{generation}
intake/{logical_run_id}/{attempt_id}/frames/QUALITY_FRAME/g{generation}
```

每个 child 只写自己的 namespace，terminal checkpoint 内保存完整 `FrameAttemptResult` 和 canonical Frame result。checkpoint 完成后，该 child 以 `frame_type + generation + child_terminal_checkpoint_sha256` 作为幂等 identity，把 canonical Frame 直接提交 Java。重复提交必须返回同一 Java Frame receipt；相同 identity 不同 hash 必须 fail closed。Python 父图不读取三个结果做业务合并。

为了避免 child 已封存但通知尚未送达造成遗失，顺序必须是“先持久化 child terminal checkpoint，再发 at-least-once completion notification”。若进程恰好在两者之间崩溃，恢复路径按 Java FrameSet 的三个预期 slot 与 child namespace 补收已经存在的 terminal checkpoint，不重调已 sealed lane 的 Provider。三个 child 不直接修改同一个 Python 父 checkpoint；Java ingress 负责全局事件序号和 current-slot CAS。

三个 Frame 允许分别停留在不同 generation，例如 `Dialogue:g1 / Dossier:g2 / Quality:g1`。Java Frame slot authority 只选择每个类型当前唯一、已确认的 sealed generation；server-only envelope 不进入 LCEL message，只用于 Frame manifest 和 Java assembly 校验。

### 8.3 并发、批量 admission 和调用边界

- 三个 child graph 属于一个 Java logical run、一个父 Graph command、一个 AgentRun attempt；child namespace 不是新的 Java attempt。
- fan-out 并发上限固定为 3；跨案件使用 command-level 公平调度器。父图必须一次预留三个 Provider permit，不能先启动一两路再无限等待第三路；无法在 deadline 内取得整组三个 permit 时，零 Provider 调用并释放全部资源。
- 取得 group lease 后，父图一次向 Java 提交 exact three Frame manifests。Java 原子校验并持久化 `PARALLEL_FRAME_SET_ADMITTED`，返回绑定 run/attempt/context/profile、三个 frame type/generation/request hash 的唯一 admission receipt；失败时父图释放 group lease，零 Provider 调用。
- 每个 admitted Frame generation 获得一次 provider-call lease。只有收到 Java durable admission ack 才允许 fan-out；本地 queue acceptance 不是 Provider 调用权威。
- 三个 child 把首包遥测、canonical typed projection item、snapshot 和 terminal 写入父运行器的有界公平 merge queue；父运行器只维护一条 attempt-scoped multiplex ingress 到 Java。每个连接只有一个 transport sequencer；重连以新 session 从 0 开始，durable cursor/per-Frame `next_local_index` 与 projection hash 负责恢复。
- Provider 首包时间只进入 telemetry，不是公开文本，不设置任何 output/final authority；只有完整闭合并通过 request-bound prefix validator 的 canonical typed item 才能成为公开 preview。
- 每个 Frame 有独立 timeout、provider call count 和至多一次同模型 Schema repair/retry。
- Retry 仍使用同一 `context_envelope_sha256`、`model_context_view_sha256`、模型、Prompt profile 和 Schema version。
- 任一 Frame 最终失败时，整个正式 command 失败；不能用缺失 Frame 生成终态。
- 不在同一 pinned epoch 内回退到单体模型节点，否则 replay 无法证明同一协议。

### 8.4 Frame 中间状态与单次业务提交

三个 Frame 是一次 Intake turn 的独立计算提交，但不是三笔正式案件变更。它们共享一条 Java multiplex ingress，以 `frame_type + generation + local_index` 区分各路；connection-scoped transport sequence 只证明当前会话内顺序，durable cursor、`next_local_index`、projection hash 和 immutable item identity 才证明重放位置。Python 不再增加第二条 final Proposal 通道。

- 三个 child terminal checkpoint 分别保存各自 generation、input/output hash、校验状态和局部 retry 进度；Java FrameSet/slot 保存三个 current child 引用和 assembly 状态，Python 父图只返回临时 outcome 摘要。
- Java 对三类 canonical typed projection item、active snapshot、generation boundary、sealed/failed 和 run terminal 全部写 durable stream/staging；不存在 arbitrary text prefix 或非持久化 semantic preview。对每路 terminal 保存 immutable Frame result 和 current Frame slot authority。这些属于执行/流式 staging 写入，不是 dossier、评分、phase 或业务 completion truth。
- Python 不生成 `IntakeParallelFrameSetV1`，不运行 final Reducer，也不 materialize 或提交 `IntakeTurnProposal`。
- 每次 Java 收到 sealed Frame 都在 technical transaction 内更新该路 slot，并检查三个 current slot。前两路只完成 staging；使 exact required set 首次齐全的那次事务执行 Java Assembler、把 assembly CAS 为 `READY`、持久化不可变 `IntakeTurnProposal` artifact 和可重放的 RoomGraphResult/FINAL 所需材料，但不得写公开正式消息、dossier revision、六项评分、下一阶段、业务 outbox 或终态回执。
- Gateway 返回 durable RoomGraphResult 后，现有 ledger 将 run/attempt 推进到 `RESULT_READY`。随后只能由现有 `TargetE2eMultiRoomOuterFinalizer` / formal commit owner 在一个正式事务中重新锁定 assembly、三 slots、V080 current authority 和 Proposal artifact，写入正式业务事实、manifest、target receipt、command completion，并把 assembly 标记为 `COMMITTED`。
- 任一 Frame 缺失或最终失败时，Java assembly 保持 `COLLECTING`/`FAILED_UNCOMMITTED`，不得生成 Proposal，也不得发生正式领域写入。

这需要 Java 增加 attempt-scoped 的技术 staging authority（immutable Frame result、每个 Frame 的 current slot、assembly state、immutable Proposal artifact），但不增加三份业务 dossier/phase。technical staging 与 formal commit 是两个明确事务边界：T1 失败不产生正式事实，T2 formal rollback 不删除已 sealed Frame 或 READY assembly。回放时 Java 已正式提交则直接返回现有 terminal result；未提交时 Python 恢复 child checkpoint，只补交/重跑缺失 Frame，或让 Java 从 READY artifact 重新进入现有 finalization。已经落入 Java 的 sealed Frame exact replay 只返回同一 receipt，不重复 Provider 或正式业务副作用。

### 8.5 单路失败重跑状态机

正常成功路径必须是三个 Frame 都在 generation 1 通过 Provider Schema、prefix validator 和 terminal validator；局部重跑只是一层故障恢复能力，不能作为掩盖高频首代失败的正常完成路径。每次 UAT 先以 `provider_call_count=1`、无 `frame_generation_reset` 作为第一道门，只有第一道门稳定通过后，才用故障注入验收下面的单路重跑状态机。

Frame child 不得把 Provider/Schema 错误传播成父图整体异常。每个 child 必须捕获可预期失败，把一个 `FrameAttemptResult` 写入自己的 terminal checkpoint：

```text
frame_type
generation
  status: SUCCEEDED | RETRYABLE_FAILED | FATAL_FAILED
context_envelope_sha256
model_context_view_sha256
input_sha256
output_sha256?
error_code?
validation_path?
provider_call_count
started_at / completed_at
```

状态转换：

```text
PENDING
  -> GROUP_RESERVED
  -> SET_ADMITTED(g1, exact three manifests)
  -> RUNNING(g1)
      -> SUCCEEDED(g1) ---------> SEALED(g1)
      -> RETRYABLE_FAILED(g1) --> RETRY_ADMIT_PENDING(g2)
                                      -> RETRY_ADMITTED(g2)
                                      -> RUNNING(g2)
                                           -> SUCCEEDED(g2) -> SEALED(g2)
                                           -> FAILED_FINAL
      -> CALL_STATE_AMBIGUOUS --> RETRY_ADMIT_PENDING(g2) or FAILED_FINAL
      -> FATAL_FAILED ----------> FAILED_FINAL
```

执行规则：

1. 父图先取得三个 Provider permit 的原子 group lease，再取得 Java exact-three durable admission receipt；只有两者都存在才一次派发三个 child。三个 child 无需等待完成 barrier，各自完成后立即写独立 terminal checkpoint。
2. 成功 child 写入 `SEALED` 后，其 result hash、generation、Model View hash 固定；父图收到或补收该 checkpoint 后，后续路由不得再次调用该模型。
3. 父 Router 只为 `RETRYABLE_FAILED` 或 durable call state 明确为 `AMBIGUOUS` 的 Frame 请求 generation+1。Java 必须先原子 admission 新 generation manifest/provider-call lease 并 CAS 当前未 sealed slot；Python 收取该 durable retry-admission ack 后才能创建/运行 child。另两个 sealed child checkpoint 与 slot 原样复用。
4. 重跑仍使用相同业务 Model View、模型和 strict Schema。允许附加固定格式的 `schema_repair_context`，其中只包含错误码和 JSON validation path，不包含上一代自由文本输出；repair context 单独计入 attempt input hash。
5. 局部重跑成功后，Java 用已 admission 的 generation 做 current-slot CAS，父图记录新 child terminal checkpoint/receipt；保留旧 generation 失败 checkpoint 和 durable item trace 供审计，不得重置其他 slot 或 Java AgentRun attempt。
6. 每个 sealed child 独立提交 Java；Python 不等待三路后再发第二个 payload。Java current slots 第一次组成 exact required set 时，才允许 Assembler 生成 `IntakeTurnProposal`。
7. 任一 slot 达到重试上限、遇到 fatal authority 错误或超过共享 `turn_deadline_at`，Python 发送该路 terminal failure，Java 将 assembly 标记为 `FAILED_UNCOMMITTED`；其他 sealed Frame 保留作审计但不得生成正式 proposal。
8. authority/envelope/hash 类 fatal 错误立即取消仍在运行的 siblings；单个 Provider/Schema 可重试错误不取消已成功或仍可完成的 siblings。
9. sealed checkpoint 写入后先进入 `SUBMIT_PENDING`；只有 Java exact Frame receipt 已确认才进入 `SEALED_ACKED`。ACK 超时只能查询或幂等重交同一 checkpoint/result hash，不能直接升 generation 或重新调用 Provider。

崩溃语义：

- group lease 已取得但 Java admission 未提交：释放/等待 lease 过期；零 Provider 调用。
- Java admission 已提交但 child 尚未开始或进程状态不明：只有 durable lease 仍为 `ADMITTED` 且 CAS 到 `STARTED` 成功的唯一 holder 可首次调用；lease 已为 `STARTED` 而调用结果不明时标记 `AMBIGUOUS`，不得在同 generation 自动重调。只有 FAILED/ABORTED、UNCOMMITTED、latest authority 和 retry budget 同时成立并取得 generation+1 durable retry-admission ack 后才可新调用。
- Frame child 已写 terminal checkpoint且 Java receipt 已存在：恢复时直接复用，不得再次调用 Provider。
- child checkpoint 已完成但 Java receipt 尚未确认：只幂等重交同一个 sealed Frame，不重跑 Provider。
- Provider 已返回但该 child terminal checkpoint 尚未完成即进程崩溃：只允许在新 generation 重跑该 Frame；系统只保证正式业务 exactly-once，不宣称外部 Provider physical exactly-once。若 Provider 支持 request idempotency key，必须使用 `admission_receipt + frame_type + generation`，但不能假设所有 Provider 都支持。
- child terminal checkpoint 已完成但通知未送达：父图通过 namespace reconciliation 补收，不重跑 Provider。
- 局部重跑期间崩溃：恢复父 checkpoint 与三个 child namespace，只重新调度未 sealed 的那个 Frame。

## 9. Java 确定性 Frame Assembler

Java Assembler 是本方案唯一的跨 Frame 权威收敛点，不调用模型。Python 只验证单个 Frame Schema 和请求绑定，不执行跨 Frame 业务合并。

### 9.1 输入验证

- Java current Frame slot 类型集合必须精确等于三项，各一份且均为 `SEALED`。
- 三个 `model_context_view_sha256` 必须一致，服务端生成的 Frame manifest 必须绑定同一 `context_envelope_sha256`、case/thread/room/epoch/fence/source message。
- Frame schema、Prompt profile 和 model id 必须符合 epoch pin；每个 Frame 可有独立 generation，但 Java 只能选择该 slot 当前唯一 sealed success，不能混入旧代结果。
- Dossier source binding 必须属于当前 actor authority。
- Quality 六项分数范围合法，缺口维度在封闭集合内，问题去重且为中文。

### 9.2 合并顺序

1. 将 Dossier delta 应用到上一持久 dossier 的临时副本。
2. 对 current delta 和历史累计字段做 authority-aware carry，禁止重新归因。
3. 用更新后的 dossier 验证/过滤 Quality gap proposals：未知、重复、已覆盖、foreign role 或无合法 binding 的候选不能阻塞。该步骤不重算或修改六项分数；无法解释的 Dossier/Quality source-binding 冲突直接 fail closed。
4. 校验 Quality 六项完整且范围合法后求和，生成唯一 `score_total`。
5. 使用服务端真值表派生 `ready_for_next_step` 和 next phase。
6. 使用 previous persisted phase 派生 current visible action，禁止本轮分数回写当前动作。
7. 生成 remark、handoff、admission、next questions 和 canonical UI projection。
8. 对完整 Java assembly input set 和生成的 terminal proposal 分别计算 hash，并把两者绑定到同一 attempt assembly record。

Quality 不依赖 Dossier 输出，因此三路可以真正并行。它看到的“当前完整输入”是 frozen previous state 加 current raw message，而不是合并后的累计 dossier。Dossier 只在 Java assembly 时参与 gap 的 source/binding 对账。必须用正负测试锁定：Dossier 证明 gap 已覆盖时只删除 gap、不改分；Dossier 与 Quality 引用 foreign/矛盾来源时 Java 拒绝 assembly，不生成现有终态 proposal。

### 9.3 必须保持的状态规则

- `score_total >= threshold` 且 canonical blocking gaps 为空，只影响下一状态是否进入 `READY_PENDING_REMARK_INVITE`。
- 如果上一状态是 `READY_PENDING_REMARK_INVITE`，本轮公开动作仍必须是 `INVITE_OPTIONAL_REMARK`，即使当前消息没有回答更早的旧问题。
- 本轮产生的新 `READY_PENDING_REMARK_INVITE` 不能在同轮改写已经发出的 `ASK_SUBSTANTIVE`；它从下一轮生效。
- legacy `total_score` 只允许在旧回放入口被丢弃，parallel Frame 新写入出现该字段直接 Schema fail。

### 9.4 映射回当前 `IntakeTurnProposal`

最终继续使用 `intake-turn-proposal.v2`，但它由 Java Assembler 在三路 sealed Frame 首次齐备的事务中确定性创建，不再由 Python 传入：

| 当前 Proposal 字段 | 来源 |
| --- | --- |
| `room_utterance` | Java DialogueComposer 基于 Dialogue candidate + server action/question bindings |
| `dossier_patch.case_story/references/party_positions/...` | previous dossier + Dossier Frame delta |
| `matrix_patch` | Dossier Frame 的 authority-valid matrix delta |
| `dossier_patch.intake_quality` | Quality Frame 六项分数、服务端求和、threshold 和 canonical gap 结果 |
| `dossier_patch.missing_information/admission/handoff_notes/party_intake_state` | Java Assembler 从同一个 canonical next-state 一次派生，禁止模型分别输出 |
| `conversation_action` | previous persisted phase |
| `readiness/missing_fields/recommendation` | canonical next-state 的外层镜像 |
| `confidence` | Quality Frame 的 `assessment_confidence` 经范围校验后映射 |
| `knowledge_answer_mode` | 服务端当前固定规则，默认 `NONE` |
| `profile_versions.prompt_version` | 三个 Prompt 版本与 Java Assembler 版本组成的已注册 composite manifest id |
| 其余 authority/hash 字段 | 当前 command、snapshot、event、activation 和 canonical proposal hash |

`dossier_patch.intake_quality` 是现有结构中承载完整六项评分的正式位置，因此不需要增加 Proposal v3；但必须补 focused contract test，证明六项、总和、threshold、gap 与外层 readiness/missing/recommendation 镜像完全一致。任何镜像差异都不得生成 READY Proposal artifact，更不得通过后续 formal transaction 进入正式领域状态。

## 10. Prompt 重构

### 10.1 公共 Authority Header

三个 Prompt 只共享一段短、位置固定、且不含任何 Frame 业务规则的 Authority Header：

```text
1. common_model_context 是本次 command 唯一、不可变的业务事实视图。
2. current_user_message 是本轮 current-source delta 的唯一来源；frozen_case_matrix 只读。
3. 只执行当前 Prompt profile 和当前 Frame Schema；不得推断、转述或补全其他 Frame。
4. 所有面向人的文本使用简体中文；机器枚举严格使用 Schema 值。
```

### 10.2 Frame 专属 Prompt

新增三个独立 Prompt profile，替代实质消息路径上的单体十段 Prompt：

- `intake_turn_dialogue_frame.md`
- `intake_turn_dossier_frame.md`
- `intake_turn_quality_frame.md`

三份专属上文的字段所有权必须物理隔离：

- Dialogue 只接收 `current_action_binding`、授权问题槽、近期对话和当前消息；不得出现六项评分、blocking-gap 判定或卷宗卡片输出规则。
- Dossier 只接收 source capacity、previous dossier、frozen matrix、current message 与合法 fact-key namespace；不得出现对话措辞、当前 action 或评分/handoff 输出规则。
- Quality 只接收标准化 dossier/claim facts、previous persisted phase、六项评分和合法 blocker 规则；不得出现 room utterance 写作规则或要求重复生成卷宗 prose。

USER/MERCHANT overlay 只说明当前业务角色和可写 source partition，不再重复阶段、评分或整份输出规则。initiator/respondent 容量由服务端信封投影，禁止 overlay 自行推断。Prompt Composer 必须为三路分别构造消息数组，不允许先拼成单体上文再用末尾附注覆盖。

### 10.3 上下文完整性与独立优化边界

本次重构不删除任何 authority 事实，但从第一版开始就隔离规则上文。业务事实先冻结为一个 common view，再按固定字段白名单投影三个只读 Frame lens；三者都绑定同一 `model_context_view_sha256`，lens hash 另行进入 Frame manifest。任何事实字段缩减仍需基于真实 token/延迟数据和 parity 测试，例如：

- Dialogue 可不携带完整历史展示卡，只保留上轮阶段、授权问题、近期对话和当前消息。
- Quality 可使用标准化 dossier snapshot 而不是全部公开 prose。
- Dossier 保留 frozen matrix、previous dossier 和 current message。

任何事实缩减必须先做 context parity 测试，不能靠主观判断删除 authority 字段；规则隔离则是本次重构的强制交付，不得延后。

## 11. Java 后端校验和正式写入

### 11.1 Java attempt-scoped multiplex Frame ingress

Provider fan-out 前先完成一次批量 admission：

```text
PARALLEL_FRAME_SET_ADMIT(exact three manifests, group lease binding)
  -> PARALLEL_FRAME_SET_ADMITTED(receipt, three provider-call leases)
  -> Java durable outbox publishes three public_frame_start controls
```

三个 Python child 不直接建立三条 Java 会话。它们先写入父运行器的 bounded fair merge queue，再由唯一版本化 ingress 会话发送。每次新建/重连必须先握手：

```text
OPEN_MULTIPLEX_STREAM:
  admission_receipt_id
  previous_durable_cursor?
  frame_resume[frame_type]: generation, next_local_index, projection_sha256?

STREAM_ACCEPTED:
  stream_session_id
  expected_transport_sequence = 0
  canonical_frame_resume[3]
```

随后才允许发送：

```text
FRAME_PROVIDER_FIRST_BYTE_METRIC
FRAME_PUBLIC_PROJECTION_ITEM...
FRAME_ACTIVE_SNAPSHOT...
FRAME_SEALED | FRAME_FAILED
```

每个 ingress 事件都带 `stream_session_id + transport_sequence + frame_type + generation`，projection item 另带当前 `local_index`。`transport_sequence` 只在当前连接内从 0 连续递增，重连后随新 session 重置；它不是 durable replay authority。Java 拒绝当前连接内 gap/reorder、resume watermark 漂移和 foreign admission receipt。durable replay 只使用 SSE cursor 与每 Frame 的 `generation + next_local_index + projection_sha256`；snapshot 覆盖的合法 item index 范围固定为 `[0, next_local_index)`，下一个新 item 的 index 必须恰好等于 `next_local_index`。

`FRAME_PROVIDER_FIRST_BYTE_METRIC` 只进入 telemetry，不作为前端内容。`FRAME_PUBLIC_PROJECTION_ITEM` 统一承载 Dialogue、Dossier、Quality prefix validator 返回的 canonical typed item；所有公开语义 item、active snapshot、generation boundary、sealed/failed 都是 durable event，不存在非持久化语义事件。Python 不再调用第二个 finalization API，也不向 Java 发送完整 `IntakeTurnProposal`。

Java 为每个 admitted Frame generation 保存独立 progress authority：

```text
provider_call_lease_state: ADMITTED | STARTED | TERMINAL | AMBIGUOUS
preview_state: NONE | OBSERVED
first_preview_next_local_index?
latest_snapshot_next_local_index?
latest_snapshot_sha256?
latest_snapshot_cursor?
latest_projection_item_sha256?
staging_state: COLLECTING | SEALED | FAILED
```

每个 `FRAME_PUBLIC_PROJECTION_ITEM` 必须由 Java 在单一事务中完成：校验当前 generation 与 exact `local_index`，写 immutable canonical item，推进 `next_local_index`，更新 projection hash；若是首项，同时写 `preview_state=OBSERVED` 与 `first_preview_next_local_index`；最后写 durable stream event/outbox 和 cursor。事务提交后 outbox 才允许客户端观察，因此不存在“已看见 preview 但 marker 未写”或“marker 已写但 preview 永久丢失”的窗口。

低于 `next_local_index` 的 exact item identity/hash replay 是 no-op 并返回原 receipt；同 index 不同 hash、跨 generation item或高于 `next_local_index` 的 gap 全部 fail closed。Java 可按冻结预算写有界 active snapshot 做压缩与快速恢复，但 snapshot 不能替代 immutable item replay authority，也不能改变 index 排他语义。

### 11.2 Java 组织三路并生成不可变 Proposal artifact

Java 每次接收 `FRAME_SEALED` 后锁定 attempt assembly，并检查 Dialogue、Dossier、Quality 三个 current slot。只有 exact set 首次齐全时，Java Assembler 才在内部生成当前既有的 `IntakeTurnProposal`：

```text
schema_version = intake-turn-proposal.v2
command_id / logical_run_id / attempt_id
case_id / room_epoch / thread_id / actor_scope_hash / agent_session_id
cognitive_revision / source_snapshot_hash / source_event_hash
conversation_action
room_utterance
dossier_patch / matrix_patch
readiness / missing_fields / recommendation
knowledge_answer_mode / confidence
profile_versions
proposal_hash
```

继续复用 `intake-turn-proposal.v2` 的字段结构、canonical JSON/hash 规则和当前 Java result/finalization DTO，但 Proposal 由 Java 内部创建，不再作为 Python→Java payload。Java 需新增 execution-scoped Frame staging：

- immutable Frame result：绑定 attempt、frame type、generation、checkpoint ref、context/model-view hash、schema/profile/model 和 canonical result hash；
- current Frame slot authority：每个 attempt/frame type 只指向当前 generation；
- attempt assembly state：`COLLECTING | READY | COMMITTED | FAILED_UNCOMMITTED`，绑定 exact input-set hash、proposal hash 和 terminal receipt。

这些表是执行/staging authority，不是三份正式 dossier。每个 `FRAME_SEALED` 必须在同一 technical transaction 中写 immutable result、CAS current slot、写 public sealed stream event/outbox 并生成 opaque Frame receipt；事务提交前不得向前端发布 sealed。前两路 sealed 只写 staging；使 exact set 首次齐全的 Frame ingress transaction 只允许完成 deterministic assembly、把 state CAS 为 `READY`、持久化 canonical Proposal bytes/hash/size/profile manifest 和 deterministic artifact ref，并准备 durable RoomGraphResult/FINAL。它不得调用 formal domain sink。

Proposal artifact 必须在 `RESULT_READY` 前拥有不可变 authority。优先使用受 PostgreSQL 唯一约束保护的 deterministic DB/URN artifact；如果沿用 object store，则写入必须发生在 formal transaction 之前并具备 exact-key/hash 幂等、immutable reader、retention 和 orphan cleanup。正式事务只读并校验 artifact，禁止在数据库正式事务中执行外部 object-store side effect。

Java 是 public sealed、Graph run-level FINAL 和正式 terminal receipt 的唯一 producer，但三者不是同一个事务级别：public sealed 属于 technical staging；Graph FINAL/RoomGraphResult 使 ledger 进入 `RESULT_READY`；正式 terminal receipt 只在现有 outer finalizer 成功后产生。Python只等待正式 terminal receipt 并结束父 Graph，永远不补发或重建 public final。

### 11.3 复用现有正式提交所有权

正式业务写入必须继续由现有 Target outer finalization 链持有。顺序固定为：

```text
technical Frame ingress / exact-three assembly READY
  -> immutable Proposal artifact + durable RoomGraphResult/FINAL
  -> AgentRun run/attempt RESULT_READY
  -> TargetE2eMultiRoomOuterFinalizer
  -> AgentRunFormalResultCommitter / JdbcIntakeFormalCommitPort
  -> one REQUIRED/REPEATABLE_READ formal transaction
  -> assembly COMMITTED + target terminal receipt
```

正式事务必须重新锁定并校验 assembly READY、三个 current Frame slots、Proposal artifact、attempt/run RESULT_READY、current case/room/fence，以及 V080 的 `event_binding_id + binding_generation + authority_version + thread/logical_sequence + command hash`。该事务一次写入 output snapshot、dossier/matrix、正式 room message、timeline、notification outbox/audit、domain operation、AgentRun manifest/run commit、target receipt 和 command completion。任一点失败全部回滚；失败状态由现有 failure recorder 或同权 companion 在新的独立事务中记录 `FAILED_UNCOMMITTED`，不能在已回滚事务内伪造失败回执。

### 11.4 Java 必须校验

- 对所有 Frame event 校验 tenant、case、thread、room、epoch、fence、actor、run/attempt/frame/generation、source snapshot/event、当前 session transport sequence 和 per-Frame exact local index。
- Frame slot CAS 必须拒绝过期 generation、同 identity 不同 hash、foreign context、不同 execution profile 和不完整 checkpoint proof。
- ingress、Frame result、slot 和 assembly 必须绑定 exact V080 `event_binding_id / binding_generation / authority_version / thread / logical_sequence / attempt command hash`；formal transaction 重新锁 current event-slot authority，superseded binding 的完整三 Frame 仍必须拒绝。
- exact set assembly 时重新校验三路 context/model-view hash、Schema/Prompt/model pin、Frame 类型各一份和 selected generation。
- `conversation_action` 由 Java 根据 previous persisted phase 派生；`room_utterance` 由 Java canonical DialogueComposer 生成。
- `dossier_patch`、`matrix_patch` 的 actor source partition、initiator authority、frozen matrix lineage 和 exact fact key 合法。
- `readiness`、`missing_fields`、`recommendation` 与六项求和及服务端阶段真值表一致；Provider 不存在独立总分。
- `profile_versions` 必须绑定 Qwen 3.7 strict Schema、三个 Frame Prompt、Graph/Java-Assembler version 和 `PARALLEL_FRAMES_V1` activation。
- 只有已完整闭合且通过 prefix validator 的 canonical projection item 可进入 stream ledger；半 JSON/raw proposal 不得入库。只有三个 current Frame slots 全部 sealed 才能生成 Proposal 和调用内部 finalizer。

### 11.5 Java 不应继续做的事

- 不对公开中文措辞做语义分类。
- 不根据自由文本猜测事实是否成立。
- 不用独立第二套自然语言规则重新评分。
- 不允许单个 Frame 完成后直接更新 dossier、phase 或 submit eligibility。

Java 继续执行权限、身份、阶段、hash、版本、幂等、唯一性和领域真值校验。三路各自有执行/staging 提交语义，但没有独立业务变更语义；完整业务结果与唯一正式回执仍然只写一次。

### 11.6 回放和 event slot

- 保留 V080 current-slot authority 与 immutable binding history。
- exact replay 必须返回同一 `IntakeTurnProposal`/terminal receipt，不再调用模型。
- Java exact Frame replay 返回同一 Frame receipt；assembly 已 COMMITTED 时，后续 Frame replay 同时返回既有 terminal receipt，不再触发合并或业务写入。
- 未提交 attempt 的 Frame 局部恢复由 LangGraph checkpoint 和 Java Frame slot 双向对账：Python checkpoint 已 sealed 而 Java 缺 receipt 时仅重交 Frame；Java 已有 sealed slot 时 Python 不得重跑 Provider。
- 只允许 FAILED/ABORTED、UNCOMMITTED、最新 attempt 精确绑定且 nonretryable logical failure 的 slot 按现有规则进入新 generation。
- Frame 局部 retry 不创建新的 Java attempt；Graph attempt 最终失败后是否新建 slot generation，继续由现有 Java recovery authority 决定。

## 12. Stream 与前端并行渲染

### 12.1 协议策略

Parallel Profile 使用新的 `agent-stream.v4`；既有 `agent-stream.v3` 的事件集、payload、单 Frame 累加器和全局 generation reset 语义保持不变。禁止以“兼容扩展”名义向严格 V3 发送新事件或额外字段。

1. Python 取得 command-level exact-three Provider group lease 后，一次向 Java 提交三个 Frame manifest。Java 原子写 `PARALLEL_FRAME_SET_ADMITTED`、三个 provider-call lease 和三条 `public_frame_start` outbox；Python 只有收到该 durable admission ack 才可 fan-out。admission 失败时 Provider 调用数必须为零。
2. 三个模型随后并发运行。Provider 首包只记录 `provider_first_byte_at[frame]` telemetry，不进入公开 SSE，也不被当作首个可见内容。
3. 三个 Provider Schema 的物理首字段都是 `public_projection_items`。增量 projector 只在一个数组对象完整闭合后产生内部 item；对应 request-bound prefix validator 返回 canonical item 后，才进入 Python bounded fair merge queue。Dossier v2 item 必须在此边界证明唯一 typed current-source `source_row` 合法，公开值直接取其 `position_summary`，technical identity 直接取其 `fact_key`。半 JSON、arbitrary string prefix、raw proposal、reasoning 或单 item 校验失败永不公开；Dossier 不再存在另一份 matrix row、candidate 或 slot trace 可与该代前缀漂移。
4. 唯一 multiplex ingress 交错发送三路 canonical item。每个 item 必须先由 Java 原子持久化 immutable item、per-Frame `next_local_index`/projection hash、durable stream event 和 outbox，事务提交后才能由 SSE relay。每路只依赖自己的 local index；connection-scoped `transport_sequence` 仅检查当前会话完整性。
5. 任一 Frame 完整 Schema 校验、accepted item trace terminal reconciliation 和 child checkpoint 全部完成后，private ingress 发送 `FRAME_SEALED`、canonical Frame payload 与 checkpoint proof。Java 在同一 staging 事务中写 immutable result/current slot、public sealed event/outbox 和 opaque receipt；完整私有 payload不转发前端。
6. exact three current slots 首次齐全后，Java technical assembly 写 READY、不可变 Proposal artifact 与 Graph FINAL/RoomGraphResult；ledger 进入 RESULT_READY 后，由现有 Target outer finalizer 在唯一正式事务中提交业务事实并写正式 terminal receipt。Python 只等待正式 terminal receipt并结束 Graph，绝不生产或补发 run-level final。

“同时更新”定义为三处区域由同一 durable admission 同时进入 active，并各自在首个 materially meaningful、authority-safe canonical item durable 后更新；不能用 start 或 Provider transport byte 代替可见 TTFT。三路不做跨 Frame 展示 barrier，但 run-level terminal 前全部属于 durable provisional projection。

### 12.2 Wire payload 与游标

一个 attempt 只有一条 multiplex ingress。每次连接握手生成新的 `stream_session_id`，`transport_sequence` 从 0 开始连续递增并只证明该连接内交付顺序；它不跨连接持久化，也不参与业务 replay。每个 Frame 另有独立 `generation`、`local_index` 和 `next_local_index`，不得用 transport 顺序推断 Frame 完成顺序。所有 public semantic item/control/snapshot/sealed/final 都由 Java 分配 durable SSE cursor。

```text
public_frame_start:
  run_id, attempt_id, frame_id, frame_type, generation,
  frame_set_receipt_id, projection_registry_version,
  delivery_class: DURABLE_CONTROL

public_frame_projection_item:
  frame_id, frame_type, generation, local_index,
  next_local_index, canonical_item_id,
  projection_kind, projection_path_id, value_kind,
  canonical_value_json? | public_text?, item_sha256,
  delivery_class: DURABLE_PREVIEW

active_frame_snapshot:
  frame_id, frame_type, generation, frame_revision,
  projection_registry_version, projection_sha256, projection,
  next_local_index,
  delivery_class: DURABLE_PREVIEW

frame_generation_reset:
  old_frame_id, new_frame_id, frame_type,
  old_generation, new_generation, reason_code,
  delivery_class: DURABLE_CONTROL

public_frame_sealed:
  frame_id, frame_type, generation,
  frame_receipt_id, next_local_index,
  result_sha256, public_projection_sha256,
  delivery_class: DURABLE_STAGING

public_frame_interrupted:
  frame_id, frame_type, generation, next_local_index,
  reason_code, retryable,
  delivery_class: DURABLE_CONTROL

final:
  final_receipt_id, final_result_hash,
  delivery_class: DURABLE_TERMINAL
```

public payload 只允许 frame identity、generation/index、已注册 projection kind/path/value、canonical item/projection hash 和 opaque receipt id。`model_context_view_sha256`、`context_envelope_sha256`、`graph_checkpoint_ref`、Provider raw payload、authority source ref 和内部 validation proof 仅存在于 private admission/seal/terminal contract，禁止进入浏览器事件。

`next_local_index` 是排他水位：snapshot/item trace 已覆盖 `[0, next_local_index)`，下一个合法新 item 必须使用该值。低于水位且 identity/hash byte-equal 的重放是 no-op；低于水位但 hash 不同、等代 index 跳跃或跨代 item 全部协议拒绝。前端、Java、Python 和 fixture 必须共享这一定义，禁止再引入 `through_local_index`。

Parallel Profile 不复用 legacy attempt flags 表示 preview：

- `public_frame_start` 不设置任何输出标志。
- 首个实际公开 projection item 在其 durable 事务中设置新的 `frame_preview_observed`，但不设置 legacy `public_output_emitted`。
- durable snapshot/sealed 设置 `frame_staging_observed`；它们不设置 `final_frame_observed`。
- 只有完整 `IntakeTurnProposal` 的唯一 run-level `final` 更新现有 `public_output_emitted/final_frame_observed`。
- Parallel attempt 恢复必须由显式 execution profile 和 Frame checkpoint/slot authority 进入 `FRAME_RESUME_OR_RECONCILE`；不得因 start/preview 落入现有泛化 `RECONCILE_ONLY`，也不得因未设置 legacy flag 而整轮重新调用三个 Provider。

断线重连先通过 session handshake 对账 Java durable cursor 与三个 Frame 的 `generation + next_local_index + projection_sha256`，再从 cursor replay durable item/control/snapshot/sealed/final。服务端可用 byte-equal active snapshot 压缩较早 item 的传输，但不能跳过未被 snapshot 覆盖的 item，也不能把 connection sequence 当 replay 水位。历史房间只读取 canonical durable terminal projection，忽略 preview/start/reset 动画。某 Frame generation+1 开始后，该 Frame 的旧 generation 事件全部忽略，但不清除其他 Frame。

### 12.3 增量 JSON 投影安全边界

- 每个 execution profile 固定一个 `projection_registry_version`，逐项声明 `frame_type`、proposal Schema、`projection_kind`、`projection_path_id`、value kind、最大字符/字节/数组项、source/fact/action/question authority、顺序规则和 semantic validator。
- Dialogue、Dossier、Quality 三类 Provider 都输出 physically-first `public_projection_items` 数组；只有完整闭合的单个 proposal object 可送入 validator。任意字符串 prefix、半 JSON、任意前端路径或 Provider 自带 canonical id/hash/revision 一律拒绝。
- prefix validator 必须 request-bound 且累计重验 prior accepted items，独立检查 actor/action/question/source/fact authority、封闭枚举、canonical value、顺序、duplicate、count/item/byte budget，并返回不可变 `PublicFrameProjectionItemV1`。Frozen authority 只做验证，不能预先合成或提前发布尚未出现在 Provider item 中的模型语义。
- Dialogue canonical item 是独立完整、可安全公开的 segment；Dossier canonical item 是单一 allowlisted typed patch；Quality canonical item 是单一 dimension/gap typed metric。前端按 type/path 做替换或追加，禁止把数字、Enum、对象或数组当字符串拼接。
- seal 前，完整 Frame Schema、Provider slot list、accepted canonical item trace 与最终 Frame fields 必须 exact reconciliation；duplicate/reorder、值漂移、缺 slot、terminal 静默新增模型语义或 projection hash 不一致均使该 generation fail closed。
- Java 确定性 composer 可在 seal/final 前追加由既有 authority 派生的 action/question/notice，但必须作为独立 canonical item 或 terminal-only deterministic suffix 明确登记；不得伪装成 Provider 流式语义。

### 12.4 前端 View Model 与安全规则

以 `case + room + run + attempt` 为作用域维护一个 immutable `durableBase` 和三个 provisional slot：

```text
replySlot
dossierSlot
qualitySlot
```

每个 slot 独立维护：

```text
frameId
frameType
generation
nextLocalIndex/frameRevision
durableCursor
resultHash
status: IDLE | RUNNING | PROVISIONAL | SEALED | DURABLE | FAILED
projection
error
```

渲染映射：

| Frame | UI 区域 | 临时更新 | 正式开放条件 |
| --- | --- | --- | --- |
| Dialogue | 左侧聊天/数字人气泡 | canonical typed segment item | Java terminal 后标记正式 |
| Dossier | 右侧案情详情、观点、事实卡 | canonical typed patch item | Java canonical dossier revision |
| Quality | 完善度、六项、核验重点 | canonical typed metric/gap item | Java canonical next state |

- 临时 Quality 分数不能开放“确认陈述”“进入证据室”或其他正式按钮。
- 事件 identity 至少包含 `protocol + attempt + frame_type + frame_id + generation + kind + local_index/revision`。同 identity 同 hash no-op；同 identity 不同 hash 显示协议冲突并停止正式提交。
- 任一旧 attempt/generation 的事件在新 generation 开始后必须忽略；`frame_generation_reset` 只清理对应 slot 的旧 provisional projection，不回滚 `durableBase` 或其他 Frame。
- 单个 Frame interrupted/failed 只在对应区域展示局部错误，不把整个 run 立即设为 ERROR；只有 run-level fatal/error 才结束整轮。其他 Frame 可保留临时内容，但全局 submit 保持锁定。
- run-level final 一旦校准 durable projection，迟到的 snapshot/sealed/reset 不得把 UI 降级回 provisional，也不得覆盖 terminal bytes/hash。
- V4 每个 canonical item 作为一个原子 UI 变更应用；禁止客户端定时打字、补帧、插值或从 terminal 文本反向伪造中间 item。可见 TTFT 以 durable item 到达并完成 DOM commit 的时间为准。
- `PARALLEL_FRAMES_V1` 禁用原有 `replyThenBoard` 串行 barrier；`MONOLITHIC_V3` 继续保持现有单卡、全局 generation reset 和 pacing 行为。
- 历史房间使用持久化投影，不重放首次进入动画或临时并行流。
- legacy V1/V2/V3 和 `MONOLITHIC_V3` 适配器继续工作；前端按 protocol/profile 显式分派，未知 V4 event/path fail closed。

### 12.5 Backpressure 与公平性

- 三个 child 共用一个有界 merge queue，但按 Frame 维护独立子队列/配额；round-robin 或等价公平仲裁不得让 Dialogue item 长期阻塞 Dossier/Quality item。
- command-level scheduler 必须 all-or-none 预留三个 Provider permit；不允许一个 command 启动一两路后长期占用资源等待第三路。permit group 与 Java admission receipt/provider-call leases 一一绑定，terminal/cancel 时按 Frame 精确释放。
- queue full、单 Frame 字节/条数预算、事件频率或 snapshot 预算超限均 fail closed 为该 Frame generation 的协议错误；不得静默丢 item 后继续 seal。发生 backpressure 的当前 Provider stream 必须立即取消以停止继续计费；是否取消另外两路由父 fatal/deadline policy 唯一决定。
- 每个 attempt 固定最大 queued events/bytes、每类 canonical item count/bytes、snapshot 最小间隔和最大 durable snapshot 数；具体数值必须在 R1 契约中冻结并进入 activation hash。
- Java durable item/outbox 不允许丢失或重排；检测 index gap/hash conflict 后客户端停止应用该 Frame 后续 item，并等待协议错误或合法新 generation，不能靠 snapshot 掩盖冲突。
- 前端断订阅不自动取消 Provider；Java ingress cancellation 只取消绑定的当前 Frame stream，是否终止整轮由父 command deadline/fatal policy 唯一决定。

## 13. 失败、局部重试与降级

### 13.1 局部重试

- Schema invalid、Provider 可重试错误或 Frame timeout 只重试失败 Frame。
- 重试不能修改模型、Schema、Prompt、上下文 hash 或 current action。
- 三路都可能在 Frame terminal 前发布已独立校验且 durable 的 canonical item。若随后完整 Schema/reconciliation 失败，必须发送该 Frame 的 `public_frame_interrupted` 和 durable `frame_generation_reset`，新的 generation 使用新 frame id；Parallel Profile 禁止复用 legacy 全局 `generation_reset`，其他两路保持不变。
- 任一 Frame 已公开的 canonical item 不得与重试代拼接；该 Frame 的 provisional projection 按 generation 整体替换，不能把两代字段混合。
- 旧 generation 失败时，Java 先原子写 `FAILED + public_frame_interrupted`；generation+1 durable retry admission 成功时，再在同一事务写 slot CAS、`frame_generation_reset` 与新 `public_frame_start` outbox。没有新 generation authority 时不得先清空旧投影或发布 reset。

### 13.2 失败终止

- 三个 Frame 任何一个超过局部重试预算，Graph command 失败且不生成 canonical terminal proposal。
- 已展示的内容标记为未提交/生成失败，不能作为正式案情或分数。
- Java 已接收的 Frame 中间结果只保留为 stream/staging 事实；只要三个 current slots 未形成 exact sealed set，就不得产生 dossier revision、phase transition 或事件完成回执。

### 13.3 不允许的降级

- 不在并行 Profile 内自动切回单体大 Schema。
- 不在同一 run 中切换 Qwen 3.8、thinking 模式或非 strict JSON 输出。
- 不用前端已有内容补齐缺失 Frame。
- 不放宽权限、角色、snapshot、hash、event-slot 或阶段校验来“容错”。

### 13.4 崩溃与恢复分类

恢复必须只依据 Java durable admission/progress/staging/final authority，不根据连接最后一帧、本地 queue 状态或前端是否看见内容猜测：

| Durable 状态 | 可恢复动作 | 禁止动作 |
| --- | --- | --- |
| Frame set 未 admission | 释放 group permit；重新执行 batch admission | 调用任一 Provider |
| `SET_ADMITTED`，provider lease=`ADMITTED` | 由唯一 lease holder CAS 为 `STARTED` 后调用一次 Provider | 未持 lease 调用；局部启动少于三路 |
| provider lease=`STARTED`，无 durable item/terminal，进程崩溃 | 标记该 generation `AMBIGUOUS`，由显式 retry authority 创建新 generation | 假定未调用并自动重用同 generation；伪称外部 Provider exactly-once |
| 已有 durable item，未 sealed | 保留 immutable item trace，interrupt/reset 后仅重试该 Frame 新 generation | 在旧 generation 续写、删除已公开 item、整轮重跑三路 |
| 部分 Frame sealed | 保留 sealed slot/receipt，只恢复缺失或失败 Frame | 重跑已 sealed Provider；用 partial set assembly |
| exact-three sealed，terminal 未提交 | Java 只重入 deterministic assembly/finalization，Provider 调用数为零 | Python 重建 Proposal/final；重新调用模型 |
| final committed | 从 terminal outbox/receipt exact replay | 生成第二条 final、第二次正式副作用 |

外部 Provider 调用无法在普通 HTTP 边界获得物理 exactly-once 保证，因此设计目标是“Java admission 后至多一个活跃 lease holder、歧义代不自动重用、业务终态 exactly-once”。该限制必须进入运行手册与测试，不得以本地 start 入队替代 durable authority。

## 14. 代码影响清单

以下是预计修改边界；最终实施前每个切片需再冻结 exact owner 和文件 hash。

### 14.1 Python

- `python-agent-service/app/graphs/intake/graph.py`：context freeze、三 child fan-out、局部 retry 和等待 Java terminal receipt；不再承担 fan-in 业务合并。
- `python-agent-service/app/graphs/intake/state.py`：parallel context、child checkpoint、Java Frame receipt 和 retry 状态。
- `python-agent-service/app/graphs/intake/lcel.py`：拆除单体调用绑定，接入三个小 LCEL node。
- `python-agent-service/app/graphs/intake/nodes.py`：context freeze、三个 Frame child、checkpoint、Java ingress client 和 retry router。
- `python-agent-service/app/graphs/intake/contracts.py`：Graph state、三个 Frame wire contract、Frame terminal receipt 和 Java terminal receipt。
- `python-agent-service/app/graphs/intake/validators.py`：Envelope/View/单 Frame 的 request-bound 结构校验；不做跨 Frame next-state 派生。
- `python-agent-service/app/agents/dispute_intake_officer/schemas.py`：三个 Frame Schema；parallel 新路径不再产出完整 `IntakeTurnProposal`。
- `python-agent-service/app/agents/dispute_intake_officer/skills/dossier/dossier_skill.py`：保留 legacy 单体 Profile；parallel 路径的正式合并迁入 Java。
- `python-agent-service/app/agents/prompts/dispute_intake_officer/`：新增三个 Frame Prompt，收敛 role overlay。
- `python-agent-service/app/harness/prompt_composer.py`：profile routing 和共享 authority header。
- `python-agent-service/app/streaming.py`：V4 三 Frame lifecycle、完整数组 item projector、三类 canonical typed projection item、局部 interruption 和 bounded fair merge backpressure；保留 V3 行为 byte-for-byte。
- `python-agent-service/app/graph_runtime/intake_executor.py`：启动/恢复三个 child，并在 Java terminal receipt 到达后结束父 Graph；不 materialize Proposal。
- `python-agent-service/app/graph_runtime/checkpoint.py`：fan-out 前 preflight、独立 Frame generation checkpoint 和 Java receipt reconciliation。
- `python-agent-service/app/graph_runtime/intake_binding.py`：legacy Proposal 路径保留；parallel 路径只绑定 Frame checkpoint/result hash。
- `python-agent-service/app/graph_runtime/intake_exchange.py`：由 proposal put 改为单一 versioned multiplex Frame event/terminal ingress client；parallel 路径不存在第二次 proposal put。
- 建议新增 `python-agent-service/app/graphs/intake/parallel_contracts.py` 和 `multiplex_frame_ingress_client.py`，避免继续扩大 `lcel.py`/`schemas.py` 的单文件耦合。

### 14.2 Java

- `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/JdbcIntakeGraphBindingStore.java`：保持现有 proposal/event binding 和 V080 slot authority；新增 Frame staging 不能旁路它的最终 event binding。
- 新增 `IntakeFrameIngressService`：校验 batch admission、multiplex session/transport sequence、per-Frame generation/exact local index、canonical item hash；原子写 durable item/progress/outbox/cursor，按预算保存 snapshot/boundary，并保存 Frame terminal receipt。禁止逐 token 或半 JSON 写 ledger/outbox。
- 新增 `JdbcIntakeFrameAssemblyStore`（名称可在实施时按现有包约定调整）：immutable Frame result、current Frame slot CAS、attempt assembly lock/state。
- 新增 `IntakeFrameAssembler`：读取 exact three sealed slots，确定性生成现有 `IntakeTurnProposal`；禁止调用模型或解析未绑定自由文本。
- `java-api-service/src/main/java/com/example/dispute/workflow/application/intake/IntakeDossierProjectionMerger.java`：接收 Java Assembler 生成的 canonical proposal，完成当前阶段和 dossier revision 的正式合并。
- `java-api-service/src/main/java/com/example/dispute/workflow/application/command/CaseCommandService.java`：保持 command/revision 冲突语义，接入 execution profile pin。
- `java-api-service/src/main/java/com/example/dispute/agentstream/application/AgentRunLedger.java`：当前 attempt-level terminal result 与唯一业务回执的执行账本 owner。
- `java-api-service/src/main/java/com/example/dispute/agentstream/infrastructure/persistence/JpaAgentRunLedger.java`：当前 terminal result 的原子持久化实现；禁止 controller/finalizer 或单个 Frame 旁路写入。
- `java-api-service/src/main/java/com/example/dispute/workflow/activity/agent/DurableAgentRunExecutionGateway.java` 与 `PostgresAgentRunV2EventStore.java`：保留 V3 单 Frame 路径，新增 V4 三槽 accumulator、Java-owned durable cursor/ingress identity 和 Parallel progress authority。
- `java-api-service/src/main/java/com/example/dispute/workflow/activity/agent/ExecuteAgentRunActivityImpl.java`：按 execution profile 区分 legacy `RECONCILE_ONLY` 与 Parallel `FRAME_RESUME_OR_RECONCILE`，等待 Java assembly terminal receipt；不再等待 Python 发送 Proposal。
- `TargetE2eMultiRoomOuterFinalizer`、`AgentRunFormalResultCommitter`、`JdbcIntakeFormalCommitPort`：保持正式事务唯一 owner；Parallel 只增加对 READY assembly、三 slots、Proposal artifact 和 V080 current authority 的锁定/校验。
- `TargetE2eIntakeFinalizationRequestResolver`、`IntakeTurnProposalLoader`：增加 Java staged DB/URN artifact reader，继续校验 immutable proposal bytes/schema/hash。
- `java-api-service/src/main/java/com/example/dispute/workflow/contract/v1/AgentStreamEvent.java` 与 `ContractTypes.java`：保留严格 V3，新增 V4 Frame payload、delivery class 和事件类型约束；禁止把 V4 字段塞入 V3 payload。
- 新增一个 forward-only migration（不得修改 V080）承载 execution-scoped Frame result/slot/assembly；复用现有 attempt-level `result_json/result_hash` 和 terminal transaction作为最终唯一业务结果。
- `AgentRunLedger`/`JpaAgentRunLedger` 是当前 terminal result 的 exact owner；`ExecuteAgentRunActivityImpl.execute/validateCompletion` 是 attempt finalization 的 exact owner。禁止以“对应 store”形式另建第二写入路径。
- 对应 DTO/JSON Schema、AgentRun finalization 和 focused tests。
- 不修改或删除 `V080__intake_event_slot_recovery_authority.sql`。

### 14.3 Frontend

- `frontend/src/stores/agentStream.js`：保留 V3 单卡路径，新增 V4 durableBase + 按 frame type/generation 路由的三个 slot、局部 reset 和 terminal-wins 状态机。
- `frontend/src/api/agentStream.js`：按 protocol 严格解析 V4 canonical typed item、delivery class、generation/index/hash；未知 event/path fail closed。
- Intake 页面聊天区、案情卡、完善度/核验重点组件：绑定各自 slot 的 provisional/durable projection。
- legacy history reader 和历史房间路径保持持久投影优先。

## 15. 实施切片与提交边界

### R0：归档和定向配置恢复

入口：规划批准，工作树保护清单已冻结。

工作：

- 只提交本计划和必要的基线记录。
- 创建原子 restoration commit，将模型、LiteLLM 路由和测试统一恢复到 Qwen 3.7 strict Schema、thinking off。
- 不触碰 hearing 未提交文件，不回退 DB migration 或 P0 历史。

出口：配置 focused test 证明所有运行入口、示例和静态契约使用同一 model id。

### R1：契约和共享上下文

工作：

- 定义 `IntakeParallelContextEnvelopeV1`、`IntakeModelContextViewV1`、三个白名单 Frame lens/独立 instruction pack、三个 Provider proposal/accepted canonical Frame Schema、typed prefix validator/reconciliation、Frame ingress/receipt，以及 Java 到当前 `IntakeTurnProposal` 的确定性映射。
- 定义 server-only Envelope、Provider-visible Model View、authorized question/action binding。
- 定义 Java execution-scoped Frame result/current-slot/assembly staging；通过新 migration 增加，且不修改 V080。
- 定义 execution profile pin、exact-three batch admission/group provider lease、multiplex session handshake、connection-scoped transport sequence、durable cursor/per-Frame index、`agent-stream.v4` version matrix和 private/public 字段白名单。
- 冻结 projection registry、三类 item count/bytes、每 Frame queue/snapshot/ledger write budget、排他 `next_local_index`，以及 `frame_preview_observed/frame_staging_observed` 与 legacy output/final flags 的状态转换。
- 编写 deterministic common-context/lens/instruction-pack hash、跨 Frame 规则字段拒绝、batch admission、byte-equal item/index fixture、Java assembly input-set、total score 和 V3 strict compatibility focused tests。

出口：Java/Python/frontend 对同一 V4 contract fixture、canonical item bytes、exclusive index、public/private whitelist 和 V3 reject fixture 达成 byte-equal；不启动 Provider 即可完成纯契约验证。

### R2：Java durable admission、V4 ingress、Frame staging 和既有 Finalizer 接入

工作：

- 实现 exact-three set admission、provider-call lease、generation retry admission 和对应 durable outbox；未获 admission ack 时没有 Provider 调用授权。
- 发布并行 Frame lifecycle 的精确 V4 wire payload、session handshake、connection-scoped transport sequence、durable cursor 和 per-Frame generation/exclusive next-local-index。
- Java 在一个 multiplex session 内接受三路 start/canonical item/snapshot/sealed/failed；每个公开 semantic item 都与 progress/outbox/cursor 原子 durable，sealed Frame 写 staging slot。Java technical ingress 是 public sealed 的唯一生产边界；Graph FINAL 与正式 terminal receipt 分别由 result terminalization 和既有 outer finalizer 持有。
- 新增 Parallel 恢复分支：start 不误置 public output，实际 preview 记录独立 frame progress；Temporal retry 只恢复缺失/未封存 Frame，不进入 legacy 泛化 `RECONCILE_ONLY`，也不重跑已封存 Provider。
- exact three sealed slots 首次齐全时，Java 自己生成当前 `IntakeTurnProposal`，校验 hash/profile/phase/score/replay，写 immutable artifact 并把 assembly 推到 READY；随后复用现有 RESULT_READY 与 Target outer finalization 链，在唯一正式事务中只提交一次业务结果。
- 保留 V080 recovery 和 exact replay。

出口：不接模型即可用 fixture 完成 exact-three admission、交错提交三类 canonical item 和三个 sealed Frame；start/item 不误置 legacy terminal flags，一个 Frame 缺失或 hash drift 时零正式写入，第三路只产生一个 READY assembly/Proposal artifact，既有 finalizer 只产生一条正式回执。crash/replay selector 覆盖 admission、preview、partial sealed、assembly READY、RESULT_READY 和 formal commit 六种 durable 边界。

### R3：Python Graph、typed projector/validator 与 Prompt/Profile

工作：

- 加入 context freeze、command-level exact-three permit reservation、Java durable batch/retry admission、三个独立 child、完整数组 item projector、三类 request-bound prefix validator/reconciliation、局部 retry、独立 checkpoint、bounded fair merge queue 和一条 attempt-scoped multiplex Java ingress。
- 新建三个小 Prompt，复用同一 immutable common context 但使用独立白名单 lens 和 instruction pack；三类 `public_projection_items` 都是物理首字段，strict Schema 分别调用 Qwen 3.7，固定 thinking off。
- 删除新路径上单体十段重复输出要求、跨 Frame Reducer、Proposal materializer 和第二次 final submit；保留 legacy profile 文件供旧 epoch 回放，但同 epoch 不混用。

出口：fake/fragmented Provider focused Graph 测试覆盖 admission ack 前零调用、三路并发、完整 item 早于 Provider completion、公平性/backpressure cancellation、乱序完成、单 Frame retry、sealed Frame 幂等重交、hash drift、缺 Frame和父图等待 Java terminal receipt；固定上下文的三类输出都通过 Schema/authority/reconciliation，字段所有权无越界。

### R4：前端三槽完整 typed 渲染

工作：

- 三个区域由同一 admitted set 同时进入 active，按 Frame 独立消费 durable canonical typed item。
- provisional 与 durableBase 分层；typed item 不做任意字符串拼接，按 exact index/hash 幂等应用，submit 只受 Java durable terminal state 控制。
- 局部 reset/error 只影响对应 Frame；terminal-wins，历史房间和旧协议保持兼容；V4 禁止客户端 typing/pacing 伪造流式证据。

出口：三路完整 typed item 交错到达、乱序、exact replay、断线 snapshot、局部失败/reset、terminal-before-frame-sealed 均不产生 UI 倒退、重复 item、跨 generation 混合或提前开放按钮；任意 public payload 不含 private authority/checkpoint/provider raw 字段。

### R5：跨服务故障窗、性能和恢复验证

工作：

- 用冻结 fixtures 和本地集成环境覆盖 admission 前后、Provider `STARTED`、首 item 前后、partial sealed、exact set、Java commit 后 Python crash 的全部恢复分类。
- 记录 admission RTT、每路 complete-item/Provider completion、durable item/outbox 写放大、Java assembly、正式 commit、前端 DOM commit 和 reconnect 收敛时间。
- 做 monolith/parallel 结构 parity，不要求自然语言逐字相等，但要求 authority、正式 proposal、幂等和终态 hash 规则一致。

出口：第 16 节全部正确性、性能、写放大、恢复和 no-leak gate 通过；任何失败均阻止 profile 启用，不通过删减 typed streaming 或放宽 authority 规避。

### R6：Shadow、激活发布和新 epoch UAT

工作：

- 在与生产一致的 activation 中先 shadow 计算但不写业务终态，对比已通过的 contract/metrics；shadow 仍执行完整三类 typed pipeline，不使用功能删减版。
- 新建隔离案件进行完整前端 UAT；旧案件不重放已提交 turn。

出口：通过第 16 节门槛后，才允许新 Intake epoch 选择 `PARALLEL_FRAMES_V1`。

## 16. 验收与观测指标

### 16.1 正确性

- 三个 Frame 使用相同 context hash，exact set 各一份。
- 新路径 Provider 输出中不存在 `total_score`、ready、phase 或 cross-frame 字段。
- current visible action 永远与上一 persisted phase 一致。
- 六项和、next ready/phase、remark/handoff 只由 Java Assembler 派生，Python parallel 路径不存在第二套正式派生值。
- 用户和商家只能写本方 current-source delta；对方历史值由服务端承接。
- 任一 Frame 失败、Java current-slot exact set 不完整、旧 generation 或 hash drift 均不得触发 Java assembly，正式业务写入为零。
- exact replay 不调用 Provider，不产生重复 message、dossier revision、event binding 或 terminal receipt。

### 16.2 性能

每个实质 turn 至少记录：

```text
command_started_at
context_frozen_at
provider_group_lease_wait_ms
frame_set_admission_started_at
frame_set_admission_acked_at
frame_set_admission_rtt_ms
frame_started_at[3]
first_provider_byte_at[3]
first_complete_projection_item_at[3]
first_projection_item_durable_at[3]
first_materially_meaningful_frontend_visible_at[3]
frame_validated_at[3]
java_frame_persisted_at[3]
java_assembly_completed_at
java_committed_at
frontend_durable_at
input_tokens[3]
output_tokens[3]
retry_count[3]
provider_queue_wait_ms[3]
provider_concurrency_observed
provider_429_count
merge_queue_high_watermark_events/bytes
per_frame_queue_wait_ms[3]
durable_projection_item_count/bytes[3]
durable_snapshot_count/bytes
stream_ledger_write_count
reconnect_snapshot_converged_ms
stale_generation_drop_count
protocol_conflict_count
```

初始目标：

- batch admission ack 前 Provider 调用数必须为零；admission 成功必须精确绑定三个 Frame/provider-call lease，三路启动时间差不超过一个本地事件循环/调度批次。
- 首个有意义前端内容 P50 不高于单体基线的 50%。
- 分别报告 admission RTT、earliest-frame、每个 Frame 及 all-three 的 P50/P95；start、Provider transport first byte 不能充当 meaningful TTFT。
- 对含有可投影内容的冻结 fixture/UAT，三路首个完整 canonical item 都必须在各自 Provider completion 前 durable 并可见；空 item 合法场景单独记录，不用伪造内容满足指标。
- 无重试时，模型阶段关键路径接近 `max(frame latency)`，而非三路之和。
- Dialogue 和 Quality 输出 token 显著小于单体输出；Dossier 不重复公开回复和评分。
- Schema invalid 可定位到单个 Frame，且不触发其他已成功 Frame 的 Provider 重跑。
- 正式提交重复率、终态重复回执和阶段错误率不得高于单体基线。
- merge queue overflow、静默丢 item、Frame starvation、旧 generation 覆盖、hash conflict 和错误 `RECONCILE_ONLY` 必须为零。
- 每个 canonical item 都必须 durable，但 item count/bytes、ledger/outbox 写放大和 snapshot 数必须低于 R1 冻结预算；不得通过拆碎 item 人为改善 TTFT。
- 任意一次断线必须通过 durable cursor/item replay 或 byte-equal snapshot/terminal 收敛到 canonical hash；历史房间临时动画次数为零。
- 三路 input token 总量、单 turn 成本、Provider queue wait/429 和连接池等待必须低于发布前冻结的预算；没有成本预算不得 rollout。

这些是 rollout gate，不是未经测量的性能承诺。若 Provider 对同账户并发串行化、P95 durable latency 超预算、三倍输入成本不可接受，或 Java/前端 round trip 抵消 TTFT 收益，则停止 Parallel rollout，不能靠放宽 Schema、增加 preview 频率或逐 token 持久化换速度。

### 16.3 最小决定性测试

- 同一 context 三 Frame 以全部六种顺序到达 Java，Assembler 生成的 proposal/hash 完全相同。
- V3 strict contract 对未知 V4 event/field 继续拒绝；V4 exact-three admission/outbox 后能先接收三个 start，再交错接收三路 canonical item，且每路 local index 与当前 session transport sequence 分别连续。
- Java batch admission 未 ack 或失败时三个 Provider 调用均为零；group permit 只能 all-or-none 获取和释放，同一 admitted manifest exact replay 返回同一 receipt。
- 模拟 Python 在 admission 前、admission 后调用前、Provider `STARTED` 后无 item、首个 item durable 前后崩溃：分别证明零调用、唯一 lease、歧义代不自动重用、已公开 item 不丢失也不在旧代续写。
- fragmented Provider JSON 的 partial item 始终隐藏；Dialogue/Quality 至少一个完整有效 item、Dossier 每条可投影 current-source row 一个有效 item在 Provider completion 前 durable/可见，Dossier 没有可投影行时允许 0 item；单 item prefix-invalid 永不进入 Java/DOM，完整 Frame trace-invalid 必须 reset 该 generation，Provider 每 Frame 无错误时只调用一次。
- `next_local_index` 覆盖 `[0,n)`：低 index 同 hash replay no-op、低 index 异 hash冲突、高 index gap 拒绝；重连重置 transport sequence 但保持 durable cursor/index/hash。
- 三个 pre-provider start 不设置 `public_output_emitted/final_frame_observed`，也不触发 legacy `RECONCILE_ONLY`；首个实际 preview 只设置 frame progress，重启后仅恢复缺失 Frame。
- Quality 只基于同一 frozen Model View 评分；Dossier 证明 gap 已覆盖时只删除 gap、不改六项，foreign/矛盾 binding 时 Java 拒绝 assembly。
- 同一 Frame exact replay 接受，不同 hash replay 拒绝。
- 一个 Frame Schema invalid 后只重试该 Frame。
- Quality 输出六项 94、无合法 gap：本轮 action 不变，next state 达标。
- previous phase 为 `READY_PENDING_REMARK_INVITE`：即使 current message 重复旧答案，Dialogue 仍邀请备注。
- Dialogue 只能生成完整 typed public segment；非备注等待态的 `remark_disposition` 固定为 `null`，备注等待态只允许 `REMARK/NO_REMARK`。Provider 试图自带 action、slot trace、question id/文本或其他服务端 authority 时拒绝，最终问题段只来自 `DialogueComposer`。
- current fact-only message + prior respondent attitude：Dossier current delta 为 `NOT_ADDRESSED`，累计展示保留 grounded prior attitude。
- arbitrary/重复/已覆盖 gap proposal 不得成为 blocking gap。
- Java Frame slots 不完整、foreign authority、错误 initiator、过期 fence、旧 generation 全部 fail closed，不生成现有 proposal。
- initiator/respondent capacity 与 current actor business role 不一致时拒绝，不允许用 actor 猜 capacity。
- 六项缺失、重复、越界，gap source 漂移，或 Provider 伪造 `total_score` 时拒绝 parallel 新写入。
- FAILED/ABORTED + UNCOMMITTED 且 latest attempt 精确证明时，同 logical event slot 只能 CAS 到 generation+1；同一旧 generation 第二次 recovery 拒绝。
- 已 COMMITTED terminal、retryable failure、latest attempt 漂移或缺 proof 时不得 recovery。
- Graph checkpoint 中的旧 Frame generation、重复 frame type 或 hash drift 均不得前移 Java current slot；Java Proposal 只能由当前 attempt 的 exact three sealed slots 内部生成。
- 前两个 sealed Frame 只能产生 staging/stream 写入；第三个 Frame 到达时只触发一次 assembly READY 和 Proposal artifact，正式提交必须等待 RESULT_READY 并由既有 outer finalizer 执行。
- 三个 Frame 并发 sealed、第三路重复投递或两个 worker 同时观察 READY 时，attempt assembly row lock/CAS 必须只允许一个 READY artifact；formal owner 只允许一个 terminal receipt。
- 模拟 Java 在单路 staging 前后、三路齐全的 assembly 中途、READY→FINAL、FINAL→RESULT_READY、formal domain write→manifest/receipt 和正式提交后崩溃：staging/READY 可幂等恢复，formal 中途不得留下部分领域事实，提交后 exact replay 必须返回同一 proposal 且零重复副作用。
- Python parallel 路径不得出现完整 `IntakeTurnProposal` 的序列化或第二次 finalization request；静态/契约测试锁定该禁令。
- 前端收到 Quality 早于 Dialogue/Dossier 时，只更新临时完善度，不开放提交；单 Frame interrupted 不把整个 run 设为 ERROR。
- partial/未知 path、duplicate path、同 index/revision 不同 hash、scalar 当字符串拼接和数组重排全部 fail closed，未通过 validator 的值不进入 Java durable event 或 DOM。
- merge queue 饱和时立即取消受影响的 Provider、明确失败且不 seal；高流量 Dialogue 不得饿死 Dossier/Quality item，兄弟 Frame 是否取消只服从父 fatal policy。
- 断线重连用新 session/transport sequence，从 durable cursor/item/latest snapshot/terminal 收敛；terminal-before-frame-sealed、局部 `frame_generation_reset` 后按 cursor + Frame identity 重放，不能混合旧代或覆盖 terminal。
- public event fixture 证明不含 context/model-view hash、checkpoint ref、raw Provider proposal/source authority；private seal fixture 保留这些验证字段。
- Java formal transaction/outbox 是唯一 final producer：commit 后 Python 立刻崩溃仍可 replay byte-equal final，Python 重连不得补发第二条 final。
- 历史房间二次进入不播放首次流式动画。

## 17. 发布、回退和兼容

- execution profile 在 Intake epoch 创建时固定：`MONOLITHIC_V3` 或 `PARALLEL_FRAMES_V1`。
- 默认先保持 `MONOLITHIC_V3`，parallel 只对 signed synthetic/shadow 或显式新 epoch 开放。
- active epoch 不允许切换 profile；旧 epoch 使用旧 Prompt/Schema 完成。
- parallel 回退只影响后续新 epoch，把 selector 切回 monolithic；不删除 parallel checkpoint 或伪造旧协议结果。
- 新 activation 必须同时固定 model id、Prompt profile、Schema version、Graph version、Java Assembler version 和 validator version。
- V080、旧 hash reader、legacy `total_score` discard 和缺前缀 replay shim 在兼容窗口内保留；移除需单独 migration/read-proof 计划。

## 18. 风险与待确认项

| 风险 | 控制 |
| --- | --- |
| 三倍输入 token 增加成本 | 完整输入先保证 authority 一致；按 Frame 做有证据的 context lens 必须作为后续独立优化，不在本重构中盲目裁剪 |
| Provider 并发限流导致半启动或三路仍串行 | command-level all-or-none group admission、公平 scheduler、连接池/permit/admission RTT、每 Frame TTFT/latency 观测 |
| Java admission 前调用 Provider，崩溃后重复调用 | exact-three manifest durable ack 与 provider-call lease 是唯一调用前置；本地 queue/start 无权授权调用 |
| 三路理解不一致 | 唯一字段所有权 + Java canonical Assembler；禁止 Frame 互相覆盖 |
| 严格 V3 无法承载并行事件 | Parallel 使用 `agent-stream.v4`；V3 事件集/payload 保持不变，protocol/profile 显式协商 |
| transport sequence 与 durable replay authority 混用 | 每次连接新 session/sequence；重放只认 Java cursor、Frame generation、exclusive next index 与 projection hash |
| 三个 start/交错 item 被单 Frame accumulator 拒绝 | V4 多 Frame accumulator + 一个 multiplex ingress/session sequencer；先过 exact-three start/item old-red/green |
| start/preview 误置 public output 并触发 `RECONCILE_ONLY` | 独立 frame progress flags + `FRAME_RESUME_OR_RECONCILE`；start 零输出权威，run final 才更新 legacy terminal flags |
| 任意文本/半 JSON 在完整校验前泄露 | 三类 physically-first proposal item；对象完整闭合后执行 request-bound prefix validator，只公开 canonical return |
| 局部 retry 与前端旧 delta 混合 | V4 frame generation、id、hash、interruption/reset 和 terminal-wins 边界；禁用 legacy 全局 reset |
| preview marker 与客户端可见顺序竞态 | item/progress/cursor/outbox 单事务，提交后 relay；无 marker-before/after-publish 窗口 |
| 三路 child 并发写入导致连接内乱序 | 三 child 先进入 bounded fair merge，单一 multiplex client 只为当前 session 分配连续 transport sequence |
| durable item 写放大抵消模型并行收益 | typed item 语义粒度、count/byte budget、批量 outbox/有界 snapshot、ledger write 指标；禁止逐 token/半 JSON event |
| public payload 泄露内部 authority | public/private Schema 分离；浏览器只收 frame identity、typed projection、hash 与 opaque receipt，不收 context/checkpoint/raw provider/source proof |
| Java Frame staging 被误当业务状态 | staging 表与 dossier/phase 表物理、类型和事务入口隔离；只有 exact three sealed slots 可触发 Assembler |
| 第三路到达时并发触发重复 assembly | attempt assembly row lock、input-set hash、状态 CAS 和唯一 proposal/receipt 约束 |
| Java commit 后 Python 未发 final | Java terminal transaction/outbox 唯一生产 final；Python 只消费 receipt，SSE 从 outbox exact replay |
| Java/Python 派生规则再次双写 | Python parallel 路径不派生 next state/Proposal；Java Assembler 是唯一实现并随 activation pin |
| 旧 persisted phase 无法读取 | dual-read 已有 `READY_PENDING_REMARK_INVITE` 等状态，迁移测试覆盖 |
| 为速度改坏 authority | 权限、actor、snapshot、fence、event-slot、hash 校验全部保留且 fail closed |

实施前必须关闭以下 blocker；任一未关闭都不得进入 R2 之后的生产实现：

1. V4 proposal/canonical/public/private JSON Schema、Java/Python/frontend byte-equal fixture 和 V3 compatibility matrix 必须冻结。
2. exact-three batch admission、command-level Provider group lease 与 admission-before-call crash window 必须有 old-red/green；未获 durable ack 时调用数严格为零。
3. multiplex session handshake、connection sequence reset、durable cursor、exclusive `next_local_index`、projection hash 与 replay identity 必须有跨语言 fixture。
4. 三类 typed prefix validator、terminal reconciliation、projection registry 和 count/item/byte budget 必须冻结；不能透传无路径半 JSON、arbitrary string prefix 或 private Frame payload。
5. `frame_preview_observed/frame_staging_observed` 的原子持久化、歧义 provider-call lease 和 Parallel recovery taxonomy 必须证明不会误入 legacy `RECONCILE_ONLY`、旧代续写或整轮重跑 Provider。
6. Java-owned sealed/final transaction/outbox 必须证明 commit 后 crash 仍 byte-equal replay，且 Python 永远不能成为第二 final producer。
7. 三路输出/连接/queue/durable item/snapshot 预算需用当前稳定案例的真实 token 与 cadence 校准；本文数值只是初始建议。

## 19. Definition of Done

只有同时满足以下条件，重构才算完成：

- Qwen 3.7 strict Schema、thinking off 在所有运行入口和 activation 中一致。
- 单次 Intake command 确实创建三个并行 Frame，且共享同一不可变 context hash。
- Parallel 使用 `agent-stream.v4` 和一条 attempt-scoped multiplex ingress；严格 V3 及历史 reader 行为不变。
- 公开输出、案情增量、评分三者完全解耦，没有跨 Frame 重复权威字段。
- current action/next state 时间语义只由 Java 确定性 Assembler 实现；Python parallel 路径不组装 Proposal。
- 三处前端独立更新，临时投影不会提前改变正式按钮或阶段。
- Frame 局部失败可局部重试，最终失败零正式写入；exact replay 零重复副作用。
- 三个 Node 先完成 exact-three Java durable admission，再共用一个公平、有界的 multiplex ingress；三类 canonical projection item、snapshot/sealed 均 durable，第三路只触发一次 Java assembly，不存在 Python 二次提交。
- start/preview/sealed 不误置 run final 或触发 legacy `RECONCILE_ONLY`；Parallel 恢复只重跑缺失且获授权的 Frame。
- 断线通过新 session handshake 从 durable cursor/item/snapshot/terminal 收敛，历史房间无 preview 动画，旧 generation 永不覆盖新代或 terminal。
- Dialogue、Dossier、Quality 的 complete typed item 都可在各自 Provider completion 前独立校验、durable 并更新 UI；任意半 JSON、raw proposal 或未治理字符串从未公开。
- Java terminal transaction/outbox 是 run-level final 的唯一 producer；公开 payload 不含 context hash、checkpoint、raw Provider 或 source authority。
- 既有 V080、角色 authority、六项评分真值、历史态度承接、矩阵 hash 与 optimistic conflict 修复无回归。
- focused tests、shadow parity、性能证据和一个新案件完整 UAT 通过。
- 回退演练证明只切换新 epoch selector 即可恢复单体 Profile，历史案件仍可读取。

在以上条件满足前，`PARALLEL_FRAMES_V1` 不得成为默认 Intake execution profile。
