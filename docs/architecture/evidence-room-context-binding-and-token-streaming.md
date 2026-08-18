# 证据室业务上下文、语义绑定与逐 Delta 流式输出契约

状态：V2 机制修订冻结设计基线（新 activation 单版本切换）
冻结日期：2026-08-18
适用范围：证据室首次进入、材料核验、纯文字补充、重新进入重放、完成举证状态推进
不改变：系统安全提示词、数字人提示词、既有安全记忆、服务端身份/权限/幂等/epoch/fence/状态机权威

版本边界：业务上下文与模型输出使用 `evidence_room_context.v2` / `evidence_turn_stream.v2`；跨服务实时传输、正式结果与提案分别升级为 `agent-stream.v3`、`evidence-turn-result.v2`、`target-e2e-evidence-turn-proposal.v2`。旧 activation、旧 checkpoint 和旧 Evidence 结果协议不迁移、不双读、不兼容。

## 1. 文档目的

本契约是后续证据室修复与重构的唯一设计指导。它统一定义：

1. 如何从接待室冻结案情矩阵和当前证据权威装配模型业务上下文；
2. 如何让模型一次生成无结构歧义的证据—事实绑定、公开回复和证据评估；
3. 如何让欢迎语、案件梳理、材料 observation、评估说明和补证请求按 Provider 实际 `delta.content` 实时输出；
4. 如何让后端只执行身份、来源、哈希、ID、引用、顺序、幂等和状态机等精准权威校验，不再用正则或文本相似度重做模型语义；
5. 如何从同一份已接受帧流派生聊天消息、右侧卡片、事实矩阵边、人工复核任务和后续庭审交接；
6. 如何保证中断、正式提交和重放不会产生第二套公开回复或重复模型调用。

本版采用一次性切换：完成 Python、Java、数据库和前端的匹配性重构后，旧 activation 直接退役，以新 activation 和 fresh case 重新启动 UAT。旧 activation 下未完成的命令、checkpoint 和房间状态不进入新 activation；旧数据只保留为历史审计事实，不作为新链路恢复来源。

“无歧义 Schema”指结构和来源绑定无歧义，不表示业务事实必须被强行判定。材料确实无关或无法明确绑定时，模型必须分别表达 `UNRELATED` 或 `AMBIGUOUS`，不能为了满足 Schema 伪造确定关系。

## 2. 已确认的重构原因

旧链路把“证据来源区间”和“待证事实”耦合为 fact-specific coordinate：同一句解析文本可能分别为多个 fact 生成多个坐标，而下游又禁止重复 source span。这样即使模型严格按目录顺序输出，第二个坐标仍可能被后端拒绝。

旧输出同时存在以下语义来源：

- `public_observations`；
- `room_utterance`；
- `evidence_assessments[].fact_links`；
- `fact_matrix_patch`；
- `verification_suggestions`；
- `internal_handoff`；
- 终态 public reply composer。

这些分区会重复描述同一事实、材料关系和用户回复，任何两处分歧都会造成流式、卡片、正式矩阵或终态不一致。

V2 的根本修复是：来源只建模一次、模型语义只生成一次、后端投影只派生一次。

## 3. 核心不变量

1. **模式权威**：`turn_mode` 只能由正式房间事件和状态机决定，模型不得自行选择模式。
2. **来源唯一**：一个物理证据来源区间只生成一个 Source Unit，Source Unit ID 不包含 fact ID。
3. **多事实显式绑定**：一个 Source Unit 可以在同一 observation 中绑定多个 fact，不为每个 fact 复制 source span。
4. **语义单源**：模型输出的 observation graph 是事实绑定、聊天、卡片和交接的唯一业务语义来源。
5. **模型负责且内容受信任**：后端不使用中文/英文正则、bigram、文本重合度、静态关键词、第二模型或终态 composer 重新判断、改写或否决公开自然语言及其业务语义。
6. **后端负责权威**：后端严格校验身份、角色、可见性、附件归属、内容哈希、Source Unit、fact ID、帧顺序、引用、幂等和状态推进。
7. **逐 Delta 公开**：公开文字一旦开始生成，按 Provider 实际 `delta.content` 到达粒度同步输出；不等待句号、完整字符串、完整 frame、assessment 或终态。
8. **无终态改写**：终态只能折叠已接受帧和已流出文字，不得再次调用模型、composer 或护栏生成另一套 `room_utterance`。
9. **正式状态后置**：公开流是 provisional preview；只有完整轮次通过终态约束后才写入正式证据图和矩阵。
10. **帧级精确重放**：已完成命令重放相同已提交帧、相同顺序、相同 header/text/hash 和相同 UTF-8 内容，不再次调用 Provider；不承诺复刻原始 token 时间间隔或 delta 切片边界。
11. **按帧持久化**：Provider `delta.content` 实时转发但不逐 delta 落库；每个 frame 完整结束后一次性持久化完整 header、完整公开文字、长度和哈希。为防止已公开后被自动重跑，首个公开 delta 前只额外持久化一次 attempt 级 `public_output_started` 标志。
12. **单版本切换**：所有生产者、持久化边界和消费者在同一新 activation 内使用新协议；不保留 V1/V2 Evidence 兼容适配器，也不允许新代码恢复旧 checkpoint。

## 4. 模式路由与模型调用边界

| 正式事件或状态 | `turn_mode` | 模型调用 | 输出重点 |
|---|---|---:|---|
| 当前角色首次正式进入当前 room epoch | `ROOM_OPENING` | 1 次 | 欢迎、案件特定梳理、2 至 3 项证据问询 |
| 当前角色提交带附件的正式批次 | `MATERIAL_REVIEW` | 1 次 | 材料接收、observation、评估、补证、人工复核、完善度 |
| 当前角色只提交文字说明 | `TEXT_FOLLOWUP` | 1 次 | 回应说明、必要追问、完善度更新 |
| 刷新或重新进入，已有正式开场 | `REENTRY_REPLAY` | 0 次 | 重放正式消息或已归档中断状态 |
| 点击完成举证 | `STATE_TRANSITION` | 0 次 | 状态机校验和推进 |

模式绑定键至少包含：

```text
case_id + room_epoch + actor_id + actor_role + command_id
```

每个参与方分别拥有开场 receipt 和私有会话上下文。同一参与方刷新页面不能再次生成开场；第一方完成举证不能推进房间，第二方完成后才由状态机封存并进入下一阶段。

文件上传后的内容提取是独立解析任务，不是 Evidence 模型调用。Markdown/纯文本使用冻结 `parsed_text`；图片只有在原始像素加载且 SHA-256 一致时才作为多模态输入。解析完成后，本轮 Evidence 语义、公开回复和评估由一次模型调用生成。

### 4.1 新 activation 切换合同

新 activation 的设计绑定统一为：

| 绑定 | 新值 |
|---|---|
| graph key | `all-rooms.target-e2e.v2` |
| graph version | `target-e2e-graph.2026-08-18.1` |
| checkpoint schema | `target-e2e-checkpoint.v2` |
| Prompt bundle | `all-rooms-prompt.target-e2e.v2` |
| room proposal source | `target-e2e-room-proposal-source.v2` |
| Evidence result | `evidence-turn-result.v2` |
| Evidence proposal | `target-e2e-evidence-turn-proposal.v2` |
| Evidence frame authority | `evidence-turn-frame.v2` |
| Evidence model context | `evidence_room_context.v2` |
| Evidence model output | `evidence_turn_stream.v2` |
| public stream | `agent-stream.v3` |
| Source Unit catalog | `evidence_source_unit_catalog.v2` |

实现和部署清单必须使用完全相同的绑定值；activation ID、worker build ID 和 task queue 由部署生成，但必须只指向这一组版本。

- 旧 activation 进入 `RETIRED` 后不再接收命令，也不向新 worker 分配旧 checkpoint；
- 新 activation 固定绑定新的 graph、checkpoint、prompt、output、policy、stream 和 Java proposal 版本；
- Python、Java、数据库迁移和前端必须作为同一 release unit 部署，任一组件版本不一致时 readiness 失败，不能降级到旧协议；
- 不编写旧 `EvidenceTurnResult`、旧 proposal、旧公开字段或旧 checkpoint 的兼容 reader/writer；
- UAT 从新 activation 创建 fresh case，旧 UAT case 不续跑、不重放到新 activation；
- 切换前必须确认旧 worker 停止领取命令、旧 activation 无新写入，新 worker 只轮询新 task queue/build binding；
- 如新 activation 验证失败，回滚方式是停止新 activation 并重新部署，而不是让同一案件跨 activation 恢复。

## 5. 输入业务上下文

### 5.1 根结构与物理顺序

模型业务上下文固定为 `evidence_room_context.v2`，按以下顺序序列化：

| 顺序 | 分区 | 内容 | 权威 |
|---:|---|---|---|
| 1 | `context_header` | Schema、矩阵/证据状态版本、room epoch、覆盖状态 | 服务端 invocation |
| 2 | `turn_contract` | 当前模式、任务目标、允许帧、数量和预算 | 状态机/模式路由 |
| 3 | `authority_scope` | case、room、actor、role、当前批次、可见附件范围 | Java/正式房间权限 |
| 4 | `frozen_case_matrix` | 冻结事实、双方正式立场、重要性和核验要求 | 接待室正式卷宗 |
| 5 | `current_evidence_batch` | 本批附件元数据、提交声明、解析和多模态状态 | 证据持久化权威 |
| 6 | `source_unit_catalog` | 当前材料唯一可引用来源单元 | 文件/解析/资产权威 |
| 7 | `accepted_evidence_graph` | 既有正式 observations、bindings、assessments 和复核状态 | 正式证据图 |
| 8 | `remaining_verification_requirements` | 未覆盖事实、来源链、形成时间、完整性和冲突缺口 | 正式状态确定性投影 |
| 9 | `private_actor_memory` | 当前角色避免重复追问所需的最小私有窗口 | 当前角色房间消息 |
| 10 | `output_contract` | 模式专属输出 Schema、帧序和枚举合同 | 版本化业务契约 |

当前业务消息或批次在对应分区内保持原始权威，不通过第三人称重写覆盖前面角色和矩阵来源。

### 5.2 `context_header`

模型可见内容只保留理解本轮所需的版本和覆盖状态：

- `schema_version`；
- `matrix_revision`；
- `evidence_state_revision`；
- `room_epoch`；
- `context_coverage = FULL | PARTIAL`。

请求哈希、上下文哈希、签名和服务端 fence 保留在 invocation envelope，不要求模型回显，不消耗模型输出。

### 5.3 `turn_contract`

每个模式使用独立的输入/输出判别联合，不能用一个包含大量空数组的通用 Schema。`turn_contract` 至少包含：

- `turn_mode`；
- 本轮业务目标；
- 允许和禁止的 frame types；
- 必需 frame cardinality；
- 最大 observation、request、review-task 数量；
- 最大公开字符预算；
- 是否允许新建事实绑定、证据评估或人工复核任务。

### 5.4 `authority_scope`

包含：

- 当前案件、房间和 epoch；
- 当前 actor ID、participant ID 和 actor role；
- 当前角色可见的 evidence IDs；
- 当前正式批次 ID 和 attachment refs；
- 私有、共享和不可见信息边界。

双方立场始终按 `USER` 和 `MERCHANT` 分区，并保留来源状态。发起方转述的商家态度不能成为商家直接权威，商家回应也不能改写发起方冻结诉求。

### 5.5 `frozen_case_matrix`

冻结矩阵是模型唯一的正式案情来源，不同时重复装入 dossier、fact targets、claim summary 和多套事实摘要。每个事实至少包含：

```json
{
  "fact_id": "FACT_001",
  "canonical_text": "商家是否承诺次日送达",
  "category": "LOGISTICS",
  "materiality": "CORE",
  "party_positions": {
    "USER": {
      "position": "用户称下单页面承诺次日达",
      "authority_status": "FORMALLY_FROZEN"
    },
    "MERCHANT": {
      "position": "商家称页面仅展示预计送达时间",
      "authority_status": "FORMALLY_FROZEN"
    }
  },
  "verification_requirements": [
    "订单页面或宣传记录",
    "完整物流节点及时间戳"
  ],
  "current_coverage": "NOT_COVERED"
}
```

`initial_case_facts` 已由表单和接待室正式过程折叠进冻结矩阵，不在证据室重新生成或作为另一份重复上下文。

### 5.6 `current_evidence_batch`

只保存本批附件元数据：

- evidence ID；
- 文件类型和大小；
- 提交方；
- `claimed_fact`；
- `truth_attested`；
- parse status；
- 原始像素或页面加载状态；
- 内容覆盖状态。

正文只在 Source Unit 中出现一次。`truth_attested` 仅表示提交方声明，不能提高真实性、关联性、完整性或置信等级。

### 5.7 `accepted_evidence_graph`

历史只传已正式接受的结构化摘要：

- observations；
- fact bindings；
- evidence assessments；
- unresolved conflicts；
- human-review status。

不重复放入旧附件全文；模型如需引用既有正式 observation，只能引用上下文提供的稳定 ID。

### 5.8 `private_actor_memory`

只保留当前角色最近必要信息：

- 已回答的问题；
- 已说明的来源、形成时间和保存方式；
- 已承诺补充的材料；
- 避免重复追问所需的最小消息窗口。

不得装入另一方私聊、不可见附件或平台内部审核内容。

## 6. Source Unit 契约

### 6.1 唯一身份

Source Unit 与 fact 无关，只代表一个可验证来源锚点：

```json
{
  "source_unit_id": "ESRC_001",
  "evidence_id": "EVIDENCE_001",
  "basis": "PARSED_TEXT",
  "content": "2026-08-12：用户通过平台联系商家……",
  "authority": {
    "parsed_content_sha256": "...",
    "start_byte": 474,
    "end_byte": 580
  },
  "coverage": "FULL"
}
```

Source Unit ID 由 evidence ID、内容权威哈希、模态、位置/页面权威、`segmenter_version` 和 `normalization_version` 确定性生成，不包含 fact ID。目录不得存在两个指向相同物理锚点的 Source Unit。

每份目录必须同时冻结：

```json
{
  "schema_version": "evidence_source_unit_catalog.v2",
  "segmenter_version": "evidence-source-segmenter.v2",
  "normalization_version": "unicode-newline-normalization.v1",
  "source_authority_hash": "...",
  "catalog_hash": "...",
  "items": []
}
```

- `segmenter_version` 决定标题、列表、段落、句子、页面和像素区域的切分规则；
- `normalization_version` 决定 Unicode、换行和 byte offset 的规范化方式；
- `source_authority_hash` 绑定本轮所有文件/解析/像素权威；
- `catalog_hash` 对完整有序目录做 canonical SHA-256；
- 完整目录在模型调用前作为不可变 invocation snapshot 保存并纳入 command/input hash；
- 同一 command、attempt、正式重放或手工重试诊断只能读取该冻结目录，不允许按当前代码重新切分；
- 修改分段或规范化算法必须同时提升版本并通过新 activation 发布，不能在同一版本下改变 Source Unit ID。

### 6.2 模态判别联合

`basis` 至少包括：

- `PARSED_TEXT`；
- `OCR_TEXT`；
- `IMAGE_PIXELS`；
- `PDF_PAGE`；
- `PLATFORM_RECORD`。

约束：

- 文本按稳定的标题、列表、段落和句子边界切分，采用固定 Unicode 和换行规范；
- OCR 文本和原始图像像素是两个不同来源，不能互相冒充；
- 只有 `IMAGE_PIXELS` 或实际加载的 `PDF_PAGE` 才能支持像素/页面观察；
- 一个 Source Unit 每轮最多形成一个 observation；同一来源涉及多个事实时，在一个 observation 的 `fact_bindings` 中表达；
- 多页文档按页面或稳定段落生成不同 Source Unit；
- 无法读取、哈希不一致或仅部分加载时，`coverage` 必须为 `PARTIAL` 或 `UNAVAILABLE`，模型不得声称核验完整原件。

本版只保留“每份当前附件必须且只能有一项 assessment”的基数约束，不新增“每份附件必须至少产生一项 observation”或 `coverage_disposition` 强制合同。模型可以在 assessment 存在的情况下不为某附件生成 observation；该取舍作为已接受的业务边界，不由后端补推或拒绝。

## 7. 输出根契约

模型统一输出：

```json
{
  "schema_version": "evidence_turn_stream.v2",
  "frames": []
}
```

这里的 `evidence_turn_stream.v2` 是模型响应 Schema，不等同于浏览器传输协议 `agent-stream.v3`。模型 header 和跨服务事件都统一使用 `frame_sequence`；传输层不得把它当成 SSE 事件序号或 durable cursor。

每个 frame 是一个固定长度为 2 的 tuple，而不是依赖 JSON 对象属性顺序：

```text
[完整结构化 header, public_text 或 null]
```

- 第一个 tuple 元素必须是完整 header 对象；
- 第二个元素对公开 frame 必须是 JSON string，对内部 frame 必须是 `null`；
- header 完整关闭后，后端先执行权威校验；
- 公开 string 开始后，后端按 Provider 实际 delta 增量解码和输出，不等待 closing quote 或 frame 结束；
- header 中包含模型业务语义，第二个元素只包含对应的公开自然语言，不另建一套绑定；
- header 只放完成引用绑定所需的 ID、枚举、短理由和有限列表；长解释必须放在第二个元素的 `public_text` 中，避免 header 生成时间吞掉该 frame 的语义首包；
- `fact_bindings[].reason`、request reason 和 assessment limitations 分别设置严格短文本预算，超预算属于结构/预算错误，不做语义改写；
- JSON Schema 使用 tuple/prefix-items 约束两元素的顺序和类型。若目标 Provider 的结构化输出方言不能可靠支持 tuple，实施前必须用 focused contract test 证明替代 wire format 仍保证“完整 header 在前、公开 string 在后”，不得退回依赖普通对象键顺序的安全假设。

模型结构化 header 不得输出以下服务端权威；该限制由 Schema 字段白名单执行，不扫描自由 `public_text`：

- 新 case、actor、room 或 epoch；
- 文件哈希、解析哈希、byte offsets 或 quote hash；
- 最终 observation ID；
- 独立 `fact_matrix_patch`；
- 独立 `internal_handoff`；
- 第二套终态 `room_utterance`。

## 8. 模式专属帧序

本节的自然语言内容要求属于 prompt contract 和 UAT 质量标准；后端运行时只校验 frame 类型、顺序、引用、基数和预算，不对 `public_text` 做内容匹配或替换。

### 8.1 `ROOM_OPENING`

```text
ROOM_WELCOME
-> OPENING_ORIENTATION
-> EVIDENCE_REQUEST x 2..3
-> ROOM_READINESS
```

`ROOM_WELCOME` 负责最快首包：

```json
[
  {
    "frame_sequence": 1,
    "frame_type": "ROOM_WELCOME"
  },
  "欢迎进入证据室。"
]
```

`OPENING_ORIENTATION` 必须引用冻结矩阵中的案件特定 focus facts：

```json
[
  {
    "frame_sequence": 2,
    "frame_type": "OPENING_ORIENTATION",
    "focus_fact_ids": [
      "FACT_DELIVERY_PROMISE",
      "FACT_LOGISTICS_DELAY"
    ]
  },
  "我正在根据接待室冻结的次日达承诺和实际延迟情况梳理待证事项，接下来会逐项说明需要补充的材料及其用途。"
]
```

开场不得声称“已收到本批材料”，不得评估尚未提交的附件。欢迎语可以简短稳定，orientation 和后续 requests 必须来自当前冻结案情矩阵，不能退化为固定话术。

### 8.2 `MATERIAL_REVIEW`

```text
MATERIAL_RECEIPT
-> EVIDENCE_OBSERVATION x N
-> EVIDENCE_ASSESSMENT x 当前附件数
-> EVIDENCE_REQUEST x 0..3
-> HUMAN_REVIEW_TASK x N
-> ROOM_READINESS
```

第一帧必须承认当前批次并引用案件核验焦点：

```json
[
  {
    "frame_sequence": 1,
    "frame_type": "MATERIAL_RECEIPT",
    "evidence_ids": ["EVIDENCE_001"],
    "focus_fact_ids": ["FACT_LOGISTICS_DELAY"]
  },
  "已收到本批物流和沟通材料，我正在核对其与延迟送达、使用影响及补偿分歧的关联。"
]
```

### 8.3 `TEXT_FOLLOWUP`

```text
TEXT_FOLLOWUP_REPLY
-> EVIDENCE_REQUEST x 0..3
-> ROOM_READINESS
```

无新附件时不得生成 observation、evidence assessment、真实性状态或新人工复核任务；只能回应当前说明、更新仍需补充的请求和展示性完善度。

## 9. 核心 frame headers

### 9.1 `EVIDENCE_OBSERVATION`

这是模型唯一的新事实绑定输出：

```json
[
  {
    "frame_sequence": 2,
    "frame_type": "EVIDENCE_OBSERVATION",
    "observation_slot": "OBS_01",
    "source_unit_id": "ESRC_001",
    "binding_status": "BOUND",
    "fact_bindings": [
      {
        "fact_id": "FACT_LOGISTICS_DELAY",
        "relation": "CONTENT_SUPPORTS",
        "reason": "材料记录了物流轨迹异常和对应时间。"
      },
      {
        "fact_id": "FACT_USAGE_IMPACT",
        "relation": "CONTENT_SUPPORTS",
        "reason": "同一记录提到可能影响预定使用安排。"
      }
    ],
    "observation_kind": "PARSED_PARTY_STATEMENT",
    "epistemic_status": "PENDING_VERIFICATION"
  },
  "材料记录了物流轨迹未更新及可能影响预定使用安排的沟通内容。"
]
```

`binding_status`：

- `BOUND`：`fact_bindings` 至少一项；
- `UNRELATED`：`fact_bindings=[]`，并给出结构化无关原因；
- `AMBIGUOUS`：不给正式矩阵建立边，提供候选 fact IDs、歧义原因并提出补充或人工复核。

`relation`：

- `CONTENT_SUPPORTS`；
- `CONTENT_CONTRADICTS`；
- `CONTEXT_ONLY`；
- `INCONCLUSIVE`。

这些关系只描述材料内容与待证事实的关系，不表示事实已被证明真实。

### 9.2 `EVIDENCE_ASSESSMENT`

每份当前附件必须且只能生成一项 assessment，header 至少包括：

- `evidence_id`；
- `observation_slots`；
- relevance；
- source-chain status；
- formation-time status；
- integrity；
- readability；
- cross-source consistency；
- authenticity status；
- capability status；
- limitations；
- conflict findings。

真实性使用有限状态：

- `UNVERIFIED`；
- `PROVISIONALLY_CONSISTENT`；
- `ANOMALY_DETECTED`；
- `UNAVAILABLE`；
- `REQUIRES_HUMAN_REVIEW`。

模型不能仅凭文件内容输出“真实有效”。Assessment 的第二个 tuple 元素是面向用户的边界化说明，并按实际 delta 流式公开；右侧卡片消费同一 header。

### 9.3 `EVIDENCE_REQUEST`

Header 至少包括：

- `request_slot`；
- `target_fact_ids`；
- `gap_codes`；
- `requested_material_kind`；
- `reason`；
- `priority`。

第二个 tuple 元素是面向用户的具体补证要求。现有 `verification_suggestions` 合并到该 frame，不保留第二套建议结构。

### 9.4 `HUMAN_REVIEW_TASK`

只进入内部队列，tuple 第二项必须为 `null`。Header 至少包括：

- `evidence_id`；
- `observation_slots`；
- `trigger_code`；
- `review_target`；
- `review_instruction`；
- `priority`。

需要向用户说明的能力限制写入对应 assessment 的公开文字，不能把内部任务、阈值或审核指令发送到聊天框。

### 9.5 `ROOM_READINESS`

必须是模型最后一个 frame，包含：

- 核心事实覆盖等级；
- 来源链覆盖等级；
- 时间和完整性覆盖等级；
- unresolved conflicts；
- remaining core fact IDs；
- human-review status；
- overall readiness level；
- 各维度理由。

如前端需要百分比，后端依据冻结权重从模型维度等级确定性映射，不重新解释证据语义。Readiness 只用于展示和补证建议，不能自行推进状态机。

## 10. 逐 Provider Delta 传输与按 Frame 持久化

### 10.1 协议版本和序号域

新 activation 使用 `agent-stream.v3`。它不兼容旧 Agent Stream 事件集合，Python 发送端、Java NDJSON/SSE reader、事件 DTO、持久化层和前端解析器必须同时升级。

`agent-stream.v3` 使用 operation 判别联合。Evidence operation 的事件集合固定为：`attempt_started`、`attempt_aborted`、`attempt_reset`、`public_frame_start`、`public_text_delta`、`active_frame_snapshot`、`public_frame_committed`、`public_frame_interrupted`、`usage`、`final`、`error`。旧 `visible_delta` 在 Evidence V3 分支中删除。`attempt_reset` 只允许替换 `public_output_started=false` 的前序 attempt；一旦可能公开过文字，只能终止并等待显式新 command。

同一 all-rooms 新 activation 中的 Intake/Hearing/Review 必须切换到 V3 envelope 和共同 attempt/final/error 规则，但可保留各自已经验证的业务事件分支；本次不重构其业务上下文。跨房间 focused regression 必须证明这些相邻流程只发生协议外壳升级，没有改变既有状态机和公开内容。

三个序号域必须分开：

- `frame_sequence`：模型 header 中的业务 frame 顺序，从 1 连续递增；
- `delta_index`：同一公开 frame 内 Provider 可见增量的顺序，从 0 连续递增；
- `durable_cursor`：只指向已经完成持久化的 frame、interruption 或 terminal 记录，用于断线重连。

实时 `public_frame_start`、`public_text_delta` 和 `active_frame_snapshot` 是瞬时传输事件，不伪装成 durable cursor。`public_frame_committed`、`public_frame_interrupted`、`final` 和 `error` 才携带 durable cursor。任何实现都不得再用同一个 `sequence` 同时表示 frame 顺序和流事件顺序。

`frame_id` 不由模型生成。header 通过后，服务端依据 `command_id + attempt_id + frame_sequence + frame_type` 确定性生成，并在所有实时事件、持久化记录和正式结果中复用。

### 10.2 实时时序

```text
Provider SSE delta.content
  -> 增量 JSON 解析
  -> 完整 frame header
  -> 协议/ID/角色/附件/Source Unit/fact/顺序/预算校验
  -> 首个公开 frame 时持久化一次 attempt.public_output_started
  -> public_frame_start（瞬时）
  -> 第二 tuple 元素 JSON string_prefix
  -> public_text_delta x N（瞬时，逐 Provider delta）
  -> frame closing quote / tuple 完整
  -> 一次事务持久化完整 frame
  -> public_frame_committed（durable）
  -> 后续 frame
  -> 完整 Schema/终态约束
  -> 正式结果事务引用全部 committed frame hashes
  -> final（正式提交后可见）
```

欢迎语 header 必须极短。Observation 只等待其短 header 完成，以便确认 Source Unit 和 fact bindings；公开文字开始后不得等待句号、closing quote、完整 frame、assessment、readiness 或模型终态。

首个公开 delta 前的 `public_output_started` 是唯一允许早于 frame 完成的持久化标志。它不保存自然语言或 token，只用于保证：一旦系统可能已经向用户公开文字，恢复逻辑就绝不自动重新调用 Provider。该标志提交后即使 Provider 尚未来得及发出字符，也保守地要求显式人工重试。

### 10.3 “逐 token”的工程定义

模型 API 暴露的是 `delta.content`，一个 delta 可能包含一个或多个 tokenizer token。系统必须：

- Provider 发来多少可见字符，就立即转发多少；
- 不人为拆字、不合并成句子、不使用前端模拟打字；
- 不等待句号、完整 string、完整 frame 或终态；
- 不把终态文本重新切片伪装成实时生成；
- 对未完成 JSON escape、Unicode surrogate 或多字节字符只缓冲到可安全解码的最小边界；
- 在内存中的当前 frame buffer 按 `delta_index` 只追加累计，完整 frame 文字必须等于这些增量的顺序拼接；
- 不逐 token/delta 写 PostgreSQL、Redis durable stream 或 checkpoint；原始 delta 切片和时间间隔不属于成功重放合同。

### 10.4 `agent-stream.v3` 公开事件

Header 验收后发送瞬时开始事件。只发送可供聊天和右侧卡片使用的公开 header 投影；完整内部 header 不进入公开 SSE：

```json
{
  "protocol": "agent-stream.v3",
  "event_type": "public_frame_start",
  "run_id": "AGENT_RUN_...",
  "attempt_id": "ATTEMPT_...",
  "frame_id": "EFRM_...",
  "frame_sequence": 2,
  "frame_type": "EVIDENCE_OBSERVATION",
  "public_header": {
    "observation_slot": "OBS_01",
    "source_unit_id": "ESRC_001",
    "binding_status": "BOUND",
    "fact_ids": ["FACT_LOGISTICS_DELAY"]
  }
}
```

公开文字按 Provider delta 直接发送：

```json
{
  "protocol": "agent-stream.v3",
  "event_type": "public_text_delta",
  "run_id": "AGENT_RUN_...",
  "attempt_id": "ATTEMPT_...",
  "frame_id": "EFRM_...",
  "frame_sequence": 2,
  "delta_index": 0,
  "delta": "材料记录了物流"
}
```

Frame 完成并一次落库后发送 durable commit：

```json
{
  "protocol": "agent-stream.v3",
  "event_type": "public_frame_committed",
  "run_id": "AGENT_RUN_...",
  "attempt_id": "ATTEMPT_...",
  "frame_id": "EFRM_...",
  "frame_sequence": 2,
  "durable_cursor": "v3:ATTEMPT_...:FRAME:2",
  "header_sha256": "...",
  "public_text_sha256": "...",
  "public_text_chars": 42
}
```

同一 live client 收到 `public_frame_committed` 时只把临时 frame 标记为 durable，不再次追加文字。重新进入或成功命令重放时，服务端直接返回已提交 frame 的完整 header/public_text snapshot，前端一次恢复完整内容，不模拟原始 token 速度。

### 10.5 Frame 持久化边界

每个完整 frame 只执行一次 frame 事务：

- 私有 frame authority 保存完整模型 header、完整 `public_text|null`、frame type/order、Source Unit/fact 引用、header/text/hash、command/attempt/actor/epoch/fence 绑定；
- 公开 frame projection 只保存允许向当前 audience 重放的公开 header、完整 public text、frame hash 和顺序；
- `HUMAN_REVIEW_TASK` 等内部 frame 只写私有 authority，不创建公开 projection 或 SSE；
- 原始 Provider delta 数组、token 时间点、隐藏 reasoning 和完整模型 JSON 不进入公开事件表；如保留私有诊断，只能使用独立受限存储和独立保留策略；
- 相同 `frame_id` 重复提交且字节完全一致时返回原 receipt；header/text/hash 任一不同则失败关闭；
- 所有 frame 完成后，终态正式事务只接受同一 command/attempt 下连续、完整、hash 匹配的 committed frames。

私有 frame authority 的最小持久化合同为：

```json
{
  "schema_version": "evidence-turn-frame.v2",
  "activation_id": "ACTIVATION_...",
  "case_id": "CASE_...",
  "room_epoch": 1,
  "command_id": "COMMAND_...",
  "logical_run_id": "AGENT_RUN_...",
  "attempt_id": "ATTEMPT_...",
  "frame_id": "EFRM_...",
  "frame_sequence": 2,
  "frame_type": "EVIDENCE_OBSERVATION",
  "visibility": "PUBLIC",
  "private_header": {},
  "public_header": {},
  "public_text": "材料记录了物流轨迹异常。",
  "header_sha256": "...",
  "public_text_sha256": "...",
  "frame_sha256": "...",
  "commit_status": "COMMITTED"
}
```

公开 projection 必须由同一事务从该 authority 精确裁剪，不允许前端或第二个异步任务重新解释 private header。Attempt 级 `public_output_started` 与 frame authority 使用独立幂等键；它们都绑定 activation/case/room epoch/command/attempt/fence。

Frame 事务失败时，已经显示的文字仍保留为 provisional，本轮失败且不得正式折叠。可控异常路径可以把内存 buffer 一次性写成 `public_frame_interrupted`，但它永远不进入证据图；进程硬故障导致 buffer 丢失时，只能依据 `public_output_started` 禁止自动重试，不能伪造丢失的局部文字。

### 10.6 断线中的活动 Frame

- 重连先按 `durable_cursor` 加载全部 committed frames；
- 若原执行进程仍存活，可额外发送不带 durable cursor 的 `active_frame_snapshot`，其内容为当前 frame 已累计完整前缀和下一个 `delta_index`；
- 前端按 `frame_id` 以 snapshot 替换同一临时 frame 的旧前缀，再继续只接受连续 `delta_index`；
- 若活动 buffer 已不存在，客户端等待该 attempt 的 committed、interrupted 或 error 状态，不能自行补字；
- durable cursor 永远不能越过尚未 frame-commit 的公开文本。

### 10.7 前端消费

- Evidence `public_text_delta` 必须绕过现有 `displayPacer`，在同一渲染节拍直接追加 Provider delta；
- `public_frame_start` 建立聊天临时 frame 和右侧卡片骨架；
- observation/assessment 卡片使用同一 frame 的 `public_header`，公开说明使用同一 frame 的增量文字；
- `public_frame_committed` 只完成 durable 标记和 hash/长度核对；
- 重放 snapshot 立即恢复完整文字，不逐字动画；
- `frame_id + delta_index` 重复事件幂等忽略，跳号、倒序或跨 frame delta 立即中断当前消费。

### 10.8 性能门槛

- 首个公开 frame 不得等待其他 frame 或模型终态；
- 首次 `public_output_started` 写入必须是短事务，目标不超过应用层首包预算；
- 从首个可安全解码 Provider delta 到浏览器收到 `public_text_delta` 的应用层附加延迟目标为 100ms 量级；
- 一轮数据库写入数应接近“1 次 attempt started 标志 + frame 数 + terminal/finalization”，不得随 token 数线性增长；
- 必须分别记录 provider start、first raw delta、first complete header、public-output marker commit、first visible delta、每个 frame commit、terminal 和 formal commit 时间；
- 首包慢必须能区分 Provider TTFT、header 生成、marker 事务、增量解析、Java relay 和前端消费延迟。

## 11. 后端精准校验

### 11.1 模型调用前

必须校验：

- 正式事件和 `turn_mode` 一致；
- 当前参与方在当前 epoch 是否已有 opening receipt；
- 当前附件属于当前正式批次和 actor；
- 当前文本解析权威为 `SUCCEEDED`；
- 文件、解析内容和多模态资产哈希一致；
- Source Unit 唯一且 span/page/asset binding 合法；
- 冻结矩阵版本和 fact IDs 完整；
- 当前上下文没有静默截断；
- mode-specific output schema 已被目标 Provider 接受。

### 11.2 Header 到达时

只做确定性检查：

- frame type 是否属于当前 mode；
- `frame_sequence` 是否连续，阶段顺序和 cardinality 是否正确；
- source unit 是否来自当前冻结目录；
- evidence ID 是否属于当前批次；
- fact IDs 是否来自冻结矩阵；
- actor、role、case 和可见范围是否一致；
- observation/request slots 是否重复；
- 一个 Source Unit 是否被重复观察；
- `BOUND/UNRELATED/AMBIGUOUS` 与字段组合是否匹配；
- assessment 是否只引用本轮已接受 observations；
- internal frame 是否错误携带公开文字。

### 11.3 公开文字增量

Header 通过后完全信任模型公开自然语言，不做前置或后置内容校验、语义重判、敏感词扫描、机器字段词法扫描、固定话术替换或第二模型审核。增量边界只负责：

- JSON string 解码；
- 单 frame 和总公开字符预算；
- 控制字符和无效 Unicode 的结构合法性；
- `frame_id/frame_sequence/delta_index` 连续性；
- 将实际 Provider delta 直接转发并只追加到当前内存 frame buffer；
- frame 完成后对完整文字计算长度与 SHA-256，并在一次 frame 事务中持久化。

若公开文字已经流出后才发生结构、预算或协议错误，立即停止当前流并标记本轮中断；不能回滚用户已看到的内容，也不能自动启动第二次 Provider 调用覆盖它。

服务端专属 case/actor/room/epoch、文件哈希、byte offsets、最终 observation ID 等仍通过输出 Schema 和 header 字段白名单进行结构性禁止；如果模型把类似文本写进自由 `public_text`，后端不扫描、不改写、不以此拒绝。本取舍属于明确接受的模型内容信任边界。

### 11.4 明确禁止

- 用中文/英文正则重新判断模型语义；
- 用 bigram、embedding 或文本相似度为 observation 选择 fact；
- 根据不同 fact 重建相同 source span；
- 用静态模板替换案件特定欢迎、梳理、observation 或补证请求；
- 终态 composer 改写已流出的公开文字；
- 在后端生成第二套事实、卡片或交接语义；
- 把每个 Provider delta 作为一行独立 durable 事件写入数据库；
- 让前端把已收到的 Provider delta 再次拆字、节流或模拟打字。

## 12. 同源派生与正式提交

模型只生成一次语义，后端从已接受 frames 确定性派生：

| 消费方 | 唯一来源 |
|---|---|
| 聊天框 | 所有 committed 公开 frame 的第二 tuple 元素按 `frame_sequence` 使用固定 `"\n\n"` 分隔符拼接 |
| observation 卡片 | `EVIDENCE_OBSERVATION` header |
| assessment 卡片 | `EVIDENCE_ASSESSMENT` header |
| 事实矩阵边 | observation 的 `fact_bindings` |
| 补证清单 | `EVIDENCE_REQUEST` header 与公开文字 |
| 人工审核队列 | `HUMAN_REVIEW_TASK` header |
| 房间完善度 | `ROOM_READINESS` header |
| 后续庭审交接 | 已接受 observation graph 的确定性摘要 |

不再让模型独立输出 `fact_matrix_patch` 或 `internal_handoff`。最终 observation ID、矩阵版本、来源引用和提交哈希由后端在正式事务边界生成。正式 `evidence-turn-result.v2` 只引用同一 attempt 下已 committed 的 frame IDs/hashes，并包含确定性派生结果；Java 不再读取旧 `EvidenceAgentTurnResult` 的重复语义字段。

终态必须验证：

- 必需 frames 和附件 cardinality 完整；
- 每份当前附件有且只有一个 assessment；
- 所有 assessment 引用已经接受的 observations；
- 所有公开终态字符串与 committed frame 的完整 public text、长度和 SHA-256 逐字一致；
- frame IDs、header hashes 和 aggregate frame-manifest hash 与正式结果引用一致；
- 最后一帧是 `ROOM_READINESS`；
- 模型结果中不存在额外公开文案或未消费绑定。

终态正式事务依次完成：锁定 command/attempt/epoch/fence、验证完整 frame manifest、写正式 room message、observation graph、assessment/cards、matrix edges、review tasks、readiness 与庭审交接、写 finalization receipt，最后才允许 `final` 对浏览器可见。任一写入失败则整体回滚；已 committed frame 仍保持 provisional，不冒充正式卷宗。

## 13. 中断、取消与重放

- `public_output_started=false` 且没有公开 frame 时，才可按既定 retry budget 自动重试同一 sealed command；
- `public_output_started=true` 后不得自动重调 Provider，即使没有任何 frame 成功 commit；
- 完整 frame 按 frame 一次归档，不归档逐 token/delta 事件；可控失败时允许一次归档 incomplete frame 的累计前缀和 interruption reason；
- 中断时前端显示“本轮输出中断”，已显示内容和已 committed frames 保持 provisional，不进入正式矩阵；
- 本版取消跨命令的半 frame continuation 和自动拼接。显式重试创建新的 command/attempt，上一失败 attempt 的完整或不完整 frames 均不作为新正式结果前缀；
- 附件批次失败后的显式重试引用同一已持久化 batch/evidence IDs，并携带 `retry_of_command_id`；不得重新上传文件、重新提交批次或复用含义不明的旧 idempotency key；
- 成功命令重放直接加载相同 committed frame snapshots、相同顺序、公开 UTF-8 文本、frame hashes 和派生结果，不再次调用 Provider，也不模拟原 token 速度；
- 活动连接重连按 10.6 的 durable frames + 可选 active snapshot 恢复；`REENTRY_REPLAY` 只加载正式记录、committed provisional frames 或已归档中断状态；
- 只有在从未公开内容的 retry 中才允许 attempt replacement；已有公开内容的 attempt 不使用 reset 覆盖用户已看见的文字。

失败诊断必须在私有 authority 中安全保留失败 `frame_sequence`、frame type、Source Unit、fact bindings、expected/actual delta index、首包和终态时间点；公开错误只暴露稳定诊断码和可操作状态，不泄露内部 header、权限信息或私有审核任务。

## 14. 上下文长度和大文件

- 冻结矩阵只装入一次；
- 当前批次正文只存在于 Source Unit；
- 历史证据只传正式结构化摘要；
- 私有消息使用最小必要窗口；
- 使用 mode-specific schema，避免向 opening 传入 assessment/review-task 全量合同；
- Source Unit 和公开帧分别设置单项与总预算；
- 大文件不得静默截断；
- 超预算时必须显式设置 `context_coverage=PARTIAL` 和 Source Unit coverage，要求拆分、受控检索或人工复核；
- 没有看到完整原件时，模型不得声称完成完整核验。

Token 治理应分别记录：系统提示、数字人提示、业务上下文、响应 Schema 和输出预算。本文只冻结业务上下文和响应契约，不调整系统/数字人提示内容。

## 15. 现有机制保留与替换

### 15.1 保留

- `EvidenceContentAuthority` 解析发布链路；
- 文件、解析文本和多模态资产哈希校验；
- actor/role/visibility 权限边界；
- 接待室冻结案情矩阵；
- AgentRun、command、epoch/fence 和 idempotency；
- Provider 原生异步 SSE；
- 增量 JSON scanner/projector 的底层扫描能力；
- 正式事务提交、final commit fence 和命令幂等框架。

### 15.2 替换

- fact-specific observation coordinate catalog；
- 公开绑定路径中的 `_derive_safe_coordinate_quote`；
- 公开绑定路径中的 `recover_parsed_text_fact_coordinates`；
- 单体 `EvidenceTurnLlmOutput`；
- 独立模型 `room_utterance`；
- 模型输出的 `fact_matrix_patch`；
- 模型输出的 `internal_handoff`；
- 终态 public reply composer；
- 后置语义正则重写；
- 等待完整 observation/frame/数组后才一次性释放公开内容的策略；
- Evidence 公开路径的前端 `displayPacer`；
- 逐 `visible_delta` 的数据库事务；
- Agent Stream V2 Evidence 事件/字段白名单；
- `EvidenceTurnResult`、`target-e2e-evidence-turn-proposal.v1` 和旧 Java `EvidenceAgentTurnResult`；
- 旧 graph/checkpoint/output binding 和旧 activation。

### 15.3 必须同步完成的下游匹配性重构

不做兼容层，以下边界必须与本协议一次性同步升级：

| 层 | 必须重构的合同 |
|---|---|
| Python 模型层 | mode-specific context/output schemas、Evidence prompt、tuple structured output |
| Python 流层 | 动态 `frames[*][1]` string-prefix projector、V3 瞬时事件、frame buffer、frame commit client |
| Python Graph 层 | `evidence-turn-result.v2`、新 checkpoint/state、frame-manifest terminal materializer |
| Java 传输层 | `agent-stream.v3` DTO/enum/parser、瞬时 relay、active snapshot、durable cursor |
| Java 持久化层 | attempt `public_output_started`、私有 frame authority、公开 frame projection、frame idempotency/hash |
| Java 正式提交 | proposal V2 loader、Evidence result V2、room message/card/matrix/review/handoff 确定性投影 |
| 前端 | V3 SSE parser、frame store、无 pacer 直接追加、卡片骨架、snapshot/reconnect/dedupe |
| 相邻房间 | Intake/Hearing/Review 升级 V3 envelope、common proposal-source v2 和 checkpoint binding，保持既有业务字段、状态机和正式提交语义 |
| Activation/Worker | 新 graph/checkpoint/output/prompt/policy/stream binding、新 task queue/build identity |

任一层仍声明旧 schema/version/visible field 时，启动 readiness 必须失败，不能在运行中动态降级。

## 16. 实施顺序

1. 冻结新 graph/checkpoint/result/proposal/prompt/policy/stream 版本与新 activation manifest；
2. 定义 `EvidenceRoomContextV2`、带 segmenter/normalization/catalog hash 的 Source Unit snapshot，以及模式专属 Frame Schema；
3. 用目标 Provider 实际 structured-output 方言验证 tuple/prefix-items、header-first 和未闭合第二字符串的增量可见性；
4. 增加“同一 Source Unit 绑定多个 facts”“header 后逐 delta”“自由 public text 不做语义拦截”的决定性 old-red；
5. 重构 Evidence context assembler 和模式路由，消除重复矩阵、正文、旧证据全文和旧输出分区；
6. 扩展增量解析器，支持有序动态 tuple 的 header-complete gate、JSON escape/Unicode 最小缓冲和 `delta_index`；
7. 建立 `agent-stream.v3` 瞬时 relay、attempt public-output marker、内存 frame buffer、frame-level 私有/公开事务和 durable cursor；
8. 建立 `evidence-turn-result.v2`、proposal V2 和 observation graph 确定性派生器，移除 matrix patch、handoff 和 terminal reply 的第二语义源；
9. 同步重构 Java V3 reader/DTO/visibility、frame store、proposal loader、正式提交与 final commit fence；
10. 同步重构前端 V3 parser/store，Evidence 文本旁路 pacer，完成 frame/card/snapshot/reconnect/dedupe；
11. 将 Intake/Hearing/Review 机械迁移到 V3/common proposal-source v2/checkpoint v2，保持业务状态机与内容合同不变；
12. 运行逐模式 focused checks、跨语言 schema/hash fixture、frame persistence/replay、断线检查和相邻房间协议外壳回归；
13. 停止旧 worker，退役旧 activation，应用数据库迁移并启动新 worker/new activation；不安装兼容 reader/writer；
14. 使用 fresh case 执行 Intake -> Evidence UAT，新 activation 内只从其自身最新安全 frame/checkpoint 继续。

## 17. 最小回归和验收门槛

### 17.1 正向

- 首次进入先按真实 Provider delta 输出欢迎语；
- orientation 引用冻结矩阵中的案件特定 facts；
- 开场生成 2 至 3 个与矩阵缺口对应的材料请求；
- 材料提交后只调用一次 Evidence 模型；
- 同一 Source Unit 在一个 observation 中绑定多个 facts；
- observation 公开文字在 header 通过后逐 Provider delta 到达前端；
- observation header 同步驱动右侧卡片；
- 每份当前附件形成一项 assessment；
- readiness 最后生成；
- 聊天、卡片、矩阵和交接来自同一 observation graph。

### 17.2 后端确定性负向

- MATERIAL_REVIEW 不能引用当前批次外附件；
- 未知 Source Unit、fact ID、错 actor、错 role、错 epoch、错 hash 失败关闭；
- 同一 Source Unit 不能生成两个 observations；
- `UNRELATED/AMBIGUOUS` 不能形成正式矩阵边；
- 内部 human-review frame 不能流入公开 SSE；
- header 出现未授权服务端字段、错 frame order/cardinality、重复 slot 或越界预算失败关闭；
- `public_text_delta` 的 frame ID、frame sequence 或 delta index 跳号/串帧时中断；
- 公开文字已流出后发生错误不能自动重调 Provider；
- 旧 activation、旧 graph/checkpoint、Agent Stream V2、旧 Evidence result/proposal 必须在 readiness/admission 阶段拒绝。

### 17.3 模型质量验收（不作为运行时文本拦截）

- opening 不应出现“已收到本批材料”；
- orientation 和 requests 应引用当前冻结案情；
- OCR-only 不应声称检查原始像素；
- assessment 不应把提交方 `truth_attested` 当成真实性结论；
- public text 应与同一 frame header 表达一致。

这些检查进入 prompt contract eval/UAT 观察，不在运行时对已经生成的 `public_text` 做正则、重写或拒绝。

### 17.4 流式、持久化与重放

- first raw visible delta 在 terminal 前出现；
- first visible delta 不等待 closing quote、完整 frame、数组 `]` 或完整 JSON；
- 前端收到的每个 `public_text_delta` 都来自实际 Provider SSE，不是人工拆字；
- Evidence live path 不调用 `displayPacer`，重放也不模拟打字；
- 首个公开 delta 前 `public_output_started` 恰好持久化一次；
- Provider delta 数增长不增加数据库事件行数；每个完整 frame 恰好一次 frame commit；
- frame commit 的完整 public text 等于本次 live deltas 顺序拼接，长度和 SHA-256 一致；
- 同一 frame commit 重放返回相同 receipt，冲突字节失败关闭；
- 同一成功命令二次读取不产生新的 Provider 调用；
- replay 的 frame 顺序、完整文字、header/text hashes 和正式结果逐字一致，不要求复刻原 delta 切片或时间；
- 活动重连只用 durable cursor 推进，未 committed frame 只能通过 active snapshot 恢复；
- 中断后临时卡片不会成为正式证据图。

## 18. 冻结结论与变更控制

本设计冻结以下决策：

1. `PUBLIC_INTRO` 被取消，模式首帧分别为 `ROOM_WELCOME`、`MATERIAL_RECEIPT` 和 `TEXT_FOLLOWUP_REPLY`；
2. opening 使用独立 `OPENING_ORIENTATION` 表达“正在根据冻结案情矩阵梳理并生成证据问询”；
3. 公开 frame 使用 `[完整 header, public_text]`，先绑定后逐 delta 输出；
4. Source Unit 与 fact 解耦，一个来源通过 `fact_bindings` 显式关联多个事实；
5. 模型语义不由后端正则或相似度二次解释；
6. 正式矩阵、卡片、人工任务和庭审交接从同一 observation graph 派生；
7. 终态不生成第二套回复；
8. 已公开后的自动 Provider retry 被禁止；
9. 公开自然语言完全信任模型，后端不做内容语义校验、扫描、改写或终态替换；
10. 模型 frame 序号、delta index 和 durable cursor 是三个独立序号域；
11. delta 实时传输但不逐 delta 持久化；首个公开输出只写一次 attempt marker，完整 frame 结束后一次持久化；
12. 私有完整 frame authority 与公开 frame projection 物理/权限隔离；
13. Source Unit 目录携带分段/规范化版本和不可变 catalog hash，并绑定 command/input hash；
14. 旧 activation 直接退役，全部相关下游按新协议同步重构，不设计兼容或 checkpoint 迁移；
15. 每份附件仍要求一项 assessment，但本版不强制每份附件产生 observation；
16. 系统提示词和数字人提示词不在本次重构范围。

如后续实现发现目标 Provider 无法支持本契约的 tuple 或 string-prefix 流，必须先形成可复现证据并进行机制级设计复核；不得用等待终态、前端模拟打字、静态固定回复、case-specific bypass、旧协议兼容层或放宽正式权威校验代替本设计。
