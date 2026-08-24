# 接待室上下文与同源流式输出契约

状态：V3 实现契约
适用范围：发起方首轮、发起方实质补充轮、被发起方实质回应轮
不改变：系统安全提示、既有记忆内容、纯备注阶段契约、服务端身份/幂等/epoch/fence/状态机权威

## 1. 设计目标

接待室每个实质轮次只调用模型一次，由同一份 Provider 结构化输出同时驱动：

1. 左侧接待官聊天回复；
2. 右侧争议轮廓卡片；
3. 案情事实矩阵语义；
4. 最后一段完整度评价和会话动作。

`initial_case_facts` 是服务端/表单提供的不可变种子。它直接用于初始卡片展示并进入模型输入，但不属于模型输出，不参与流式生成，也不能被模型改写。

模型生成的案情语义是业务语义来源。后端不再用正则重新判断态度，不再改写模型摘要、争议焦点、缺口或风险语义，也不重新计算完整度。后端仅执行响应 Schema 解码，以及身份、角色、来源、事实 ID、哈希、矩阵版本、幂等和状态机等服务端权威边界。

## 2. 输入装配

### 2.1 物理顺序

进入 User Prompt 的上下文段固定按以下顺序序列化：

| 顺序 | 段 | 内容 | 权威 |
|---:|---|---|---|
| 1 | `case_identity` | 案件、房间、当前角色、固定业务引用 | Java/服务端过滤 |
| 2 | `initial_case_facts` | 首轮不可变表单事实 | Java/表单输入 |
| 3 | `frozen_case_matrix` | 上轮正式矩阵的允许投影 | 持久化矩阵 |
| 4 | `previous_dispute_outline` | 当前角色可见的上一版轮廓与接待状态，不重复矩阵 | 持久化卷宗投影 |
| 5 | `recent_dialogue_messages` | 当前私有会话最近 5 条历史消息 | 房间消息历史 |
| 6 | `current_user_message` | 当前参与方本轮原始输入 | 当前认证房间消息 |

当前消息在物理上最后出现，便于模型把最新补充作为本轮主要任务；它不能覆盖前面段的身份、来源和冻结矩阵权威。

### 2.2 保留优先级与提示顺序分离

Token 超限时先按“必需段、保留优先级”选择段，选择完成后再按上表的 `prompt_order` 排列。这样当前消息和冻结矩阵可以具有最高保留优先级，同时仍维持稳定的阅读顺序。

以下段在相应阶段必须存在，放不下时失败关闭而不是静默猜测：

- `case_identity`；
- 首轮的 `initial_case_facts`；
- 普通轮的 `current_user_message`；
- 被发起方轮的 `frozen_case_matrix`。

### 2.3 隐私裁剪

- 最近消息只来自当前角色的私有接待会话，最多 5 条；不装载另一方私聊原文。
- 被发起方只能看到冻结矩阵的结构化事实和诉求投影，以及自己的接待状态。
- `previous_dispute_outline` 不再重复 `frozen_case_matrix`，避免相同事实占用两份上下文。
- 原始消息、业务 ID 和矩阵键保持原值，不经过文案本地化或第三人称重写。

## 3. 输出 Schema

根对象只允许两个字段，并按以下顺序生成：

```text
room_utterance
ordered_sections
```

`ordered_sections` 是固定长度为 10 的 tuple，而不是依赖 JSON 对象键顺序的普通映射：

| sequence | kind | 右侧/终态投影 |
|---:|---|---|
| 1 | `CASE_MATRIX` | 正式矩阵的模型语义输入；服务端生成 ID、来源、哈希和版本 |
| 2 | `CASE_STORY` | `case_story` |
| 3 | `PARTY_POSITIONS` | `party_positions` |
| 4 | `CLAIM_AND_RESPONSE` | `claim_resolution`、`respondent_attitude`；被发起方轮的发起方诉求由冻结权威约束为精确回显 |
| 5 | `DISPUTE_FOCUS` | `dispute_core_state`、`dispute_focus` |
| 6 | `VERIFICATION_FOCUS` | `dispute_core_state.next_verification_focus` |
| 7 | `RISK_ASSESSMENT` | `risk_assessment` |
| 8 | `MISSING_INFORMATION` | `missing_information` |
| 9 | `HANDOFF_SUMMARY` | 展示说明；正式备注状态和来源仍由状态机注入 |
| 10 | `TURN_EVALUATION` | `intake_quality`、`admission`、`conversation_action` |

除 `CASE_MATRIX` 外，各 section 都是本轮完整累计值，不是增量补丁。`initial_case_facts` 不得出现在模型输出。

纯备注阶段继续使用独立的 `IntakeRemarkAcknowledgementLlmOutput`；被发起方自动开场继续使用只含正式话术的 opening 契约。它们不伪造一次新的实质卡片生成。

## 4. 真实流式时序

```text
Provider SSE bytes
  -> room_utterance string_prefix
  -> ordered_sections[0] 完整对象
  -> ordered_sections[1] 完整对象
  -> ...
  -> ordered_sections[9] TURN_EVALUATION
  -> 完整 JSON Schema 解码
  -> 服务端来源/状态权威装配
  -> 正式提交
```

- `room_utterance` 按 Provider 的实际 `delta.content` 字符前缀立即公开，因此首包不等待矩阵、卡片或完整 JSON。
- 只有 `room_utterance` 的 closing quote 到达后，右侧 section 才开放，保证左侧回复先出现。
- 每个 section 的 JSON 对象闭合后立即以一个原子事件公开，不等待数组 `]` 或模型终态。
- 前端把每个 section 映射到已有卡片，不把结构化 JSON 放进聊天消息。
- 超过单事件上限的可选卡片不能拆成无效 JSON；该卡片跳过临时流式投影，终态刷新仍恢复正式卷宗。
- `TURN_EVALUATION` 固定最后生成，完善度不会抢占回复首包时间。

## 5. 被发起方语义与来源绑定

被发起方实质轮的 `CASE_MATRIX.respondent_claim` 使用 `respondent-claim-binding.v1`：

- 有本人直接诉求态度时，模型输出 `CURRENT_ACTOR_DIRECT`、当前认证角色、当前消息中的逐字最小引文，以及关联的当前来源事实键；
- 只补充事实而没有表达诉求态度时，输出 `NO_DIRECT_POSITION`，不创建新的直接态度权威；
- 服务端只验证角色、引文是否来自当前认证消息、关联键是否属于当前来源事实；不再用中文/英文正则重新判断引文到底是同意、拒绝还是替代方案；
- `source_quote` 只在本轮内用于来源绑定，不写入跨方正式矩阵或最终卷宗；正式卷宗只保留消息 ID 和来源类型。

`CLAIM_AND_RESPONSE.respondent_attitude.source_attribution` 在 Provider Schema 中显式区分 `INITIATOR_REPORTED / RESPONDENT_DIRECT / NO_DIRECT_POSITION`。发起方转述的对方态度只能作为“发起方单方陈述（主观）”，不会创建被发起方直接态度；若模型判定为尚未直接回应，终态只保留中性的“尚未回应”对象，不把具体转述内容与该来源混装。被发起方轮的 `claim_resolution` 不是重新生成的语义权威：Provider Schema 把上一版完整发起方诉求约束为常量，终态再从冻结卷宗原样投影，因此当前被发起方只拥有自身回应和新事实语义。

## 6. 完整度评价

模型在最后一个 `TURN_EVALUATION` 按固定标准一次生成：

| 分项 | 上限 | 评价内容 |
|---|---:|---|
| `references` | 15 | 订单、售后、物流等引用是否足以定位案件 |
| `event_story` | 20 | 时间、对象、金额、经过和当前状态是否清楚 |
| `party_positions` | 20 | 当前应接待方立场及已知双方分歧是否清楚 |
| `requested_resolution` | 15 | 发起方诉求、金额/对象和理由是否清楚 |
| `risk_and_conflicts` | 15 | 核心冲突、争议事实和风险点是否明确中立 |
| `next_action_clarity` | 15 | 缺口、问题或交接动作是否明确且不索要证据 |

模型只输出六项 `score_breakdown`，不输出独立总分；服务端以六项之和生成唯一最终完善度。阈值固定为 85：

- 六项分数之和 `>= 85` 且无阻塞缺口：`ready_for_next_step=true`；
- 否则保持 `ASK_SUBSTANTIVE / NOT_READY`；
- 后端不重新评分，也不把缺口或完整度改写成固定话术；
- 服务端状态机仍要求“首次达标轮必须来自认证参与方消息”，并负责正式备注分区和交接来源。

## 7. 重放与失败语义

- Provider 原始结构化输出只调用一次；聊天和卡片来自同一输出，不存在第二次“卡片补写”调用。
- 同一 AgentRun 的流事件是 append-only，并按 attempt/sequence 去重；断线重连只重放已持久化事件。
- `attempt_aborted` 或 `attempt_reset` 会清空临时卡片覆盖层，恢复最近正式卷宗。
- 正式提交后，前端以持久化卷宗替换临时卡片；模型输出中的矩阵临时键、来源引文和服务端生成字段不会成为第二套权威。
- 角色、引文来源、事实键、矩阵 CAS、请求哈希、epoch/fence 或状态机不一致时失败关闭；不会回退到正则猜测或静态固定回复。

## 8. 上下文预算

接待室上下文段预算固定为 20,000 个保守估算 token，为 32,000 输入窗口预留系统提示、响应 Schema 和 Prompt 包装空间。当前 V3 的静态估算为：

- 系统提示：7,057 字符，约 1,765 token；
- 发起方 Schema：14,843 字符，约 3,711 token；
- 被发起方 Schema：16,417 字符，约 4,105 token。

按较大的被发起方 Schema 估算，20,000 段预算加静态提示约为 25,870 token，仍为包装开销和 tokenizer 偏差保留约 6,000 token。估算采用统一的“字符数除以 4 向上取整”治理口径，不冒充供应商精确 tokenizer 结果。
