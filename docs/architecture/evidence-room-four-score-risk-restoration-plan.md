# 证据室材料四项评分与总体风险恢复计划

状态：实施中的冻结计划

日期：2026-08-20

适用范围：证据室 `MATERIAL_REVIEW` 的材料评估、风险表达、人工复核派生和材料详情展示

基线文档：`docs/architecture/evidence-room-context-binding-and-token-streaming.md`

## 1. 目标

本次变更把证据室当前枚举式材料评估恢复为旧版四项数值评分，并将开放的多风险标签收敛为模型生成的单一总体风险等级。

本次只改变：

1. `EVIDENCE_ASSESSMENT` 的评分字段；
2. 总体风险字段；
3. 四项评分与总体风险对应的人工复核规则；
4. Java 持久化、前端材料详情和后续冻结卷宗对新评估字段的匹配性投影；
5. 与上述字段直接相关的提示词规则说明。
6. 删除模型生成的人工复核决定、指导任务、审核目标和审核指引，改由后端只生成复核原因详情。

本次不改变证据室输入上下文、模式路由、事实绑定、公开文本顺序、逐 Delta 流、Frame 持久化、幂等、重放或状态机。

## 2. 冻结不变量

### 2.1 输入上下文不变

模型仍只读取现有 `evidence_room_context_v2`，物理装配顺序保持：

1. `context_header`；
2. `turn_contract`；
3. `authority_scope`；
4. `frozen_case_matrix`；
5. `current_evidence_batch`；
6. `source_unit_catalog`；
7. `accepted_evidence_graph`；
8. `remaining_verification_requirements`；
9. `private_actor_memory`；
10. `output_contract`。

系统安全提示词、数字人身份、安全记忆、当前角色定向规则和上下文裁剪策略均不改变。

### 2.2 模式不变

保留三个现有模式：

- `ROOM_OPENING`；
- `MATERIAL_REVIEW`；
- `TEXT_FOLLOWUP`。

评分只出现在包含本轮附件的 `MATERIAL_REVIEW`。`ROOM_OPENING` 和无附件的 `TEXT_FOLLOWUP` 不生成材料评分。

### 2.3 事实绑定不变

`EVIDENCE_OBSERVATION` 继续作为唯一事实绑定语义来源：

- `source_unit_id` 指向当前 Source Unit；
- `binding_status` 仍为 `BOUND`、`UNRELATED` 或 `AMBIGUOUS`；
- `fact_bindings` 由模型根据材料说明、实际材料内容和 `frozen_case_matrix` 中的 `fact_id` 直接生成；
- 后端只确认 `evidence_id` 属于当前批次、`source_unit_id` 属于当前附件、`fact_id` 属于当前冻结矩阵，并确认当前角色对附件具有正式归属或可见权限；
- 上述 ID、角色和附件权威通过后，正式事实矩阵边继续直接来自 observation 的 `fact_bindings`；
- `EVIDENCE_ASSESSMENT` 只通过 `observation_slots` 引用 observation，不再维护第二套 `fact_links`。

ID、角色和附件校验只判断正式成员关系与权限，不判断材料是否应当绑定该事实，不重做模型语义。不恢复旧版的文本相似度匹配、bigram 恢复、相关性压到 `0.49`、后端猜测 `fact_id` 或后端改写关系类型。

## 3. 输出顺序完全冻结

### 3.1 根输出顺序不变

根 JSON 继续按照以下顺序生成：

```json
{
  "schema_version": "evidence_turn_stream.vNext",
  "lead_public_text": "首帧公开文本",
  "frames": []
}
```

`lead_public_text` 必须继续紧跟 `schema_version`，不能在它之前增加评分 header、风险 header、规则说明、占位句或其他业务字段。

### 3.2 各模式 Frame 顺序不变

`ROOM_OPENING`：

```text
lead_public_text / ROOM_WELCOME
→ OPENING_ORIENTATION
→ EVIDENCE_REQUEST × 2..3
→ ROOM_READINESS
```

`MATERIAL_REVIEW`：

```text
lead_public_text / MATERIAL_RECEIPT
→ EVIDENCE_OBSERVATION × 0..n
→ EVIDENCE_ASSESSMENT × 当前附件数
→ EVIDENCE_REQUEST × 0..3
→ ROOM_READINESS
```

`TEXT_FOLLOWUP`：

```text
lead_public_text / TEXT_FOLLOWUP_REPLY
→ EVIDENCE_REQUEST × 0..3
→ ROOM_READINESS
```

不得为了评分恢复而把 assessment 放到 observation 前、把内部人工复核任务放到公开回复前，或把评分汇总放到 `lead_public_text` 前。

本计划明确完全信任模型遵循上述生成顺序。顺序、Frame 数量、预算和每份附件一项 assessment 由提示词及本次 Provider Scheme 约束，业务后端不再进行第二次顺序、数量、预算或 assessment 基数校验。该信任边界属于已接受的设计决定，不视为与“输出顺序完全冻结”矛盾。

### 3.3 流式顺序不变

1. 模型最先生成 `lead_public_text`；其 Provider delta 立即公开。
2. 后续每个公开 Frame 仍先完成 header，再立即逐 Provider delta 输出该 Frame 的 `public_text`。
3. 不等待完整 Frame、完整数组、完整 JSON 或模型终态才公开已经产生的文本。
4. 模型不再生成 `HUMAN_REVIEW_TASK`；因此不存在内部审核指令进入聊天框的路径。
5. 完整 Frame 后统一持久化；不逐 token 持久化。
6. 终态只折叠同一批已生成文本，不生成第二套书记官回复。

## 4. 新的材料评估 Scheme

每份当前附件仍恰好对应一个 `EVIDENCE_ASSESSMENT`。评估 header 改为：

```json
{
  "header": {
    "frame_sequence": 4,
    "frame_type": "EVIDENCE_ASSESSMENT",
    "evidence_id": "EVIDENCE_...",
    "observation_slots": ["OBS_01"],

    "authenticity_score": 0.78,
    "authenticity_score_explanation": "材料正文能够读取，但当前缺少平台原始导出来源，真实性只能作初步判断。",
    "relevance_score": 0.92,
    "relevance_score_explanation": "材料中的物流单号和签收时间直接对应冻结矩阵中的物流签收事实。",
    "completeness_score": 0.66,
    "completeness_score_explanation": "材料包含主要物流节点，但没有完整运输轨迹和平台导出上下文。",
    "assessment_confidence": 0.81,
    "assessment_confidence_explanation": "解析文本清晰，能够支持本次文本范围内的核验判断。",

    "risk_level": "MEDIUM",
    "risk_explanation": "材料内容能够对应物流事实，但来源链仍不完整，综合判断为中风险。",

    "source_basis": [
      "解析文本显示物流单号和签收时间"
    ],
    "formation_time_assessment": "材料中的时间能够部分对应物流节点",
    "findings": [
      {
        "finding_type": "LOGISTICS_RECORD",
        "description": "材料中可见物流单号和签收时间"
      }
    ],
    "limitations": [
      "当前材料未包含物流平台原始导出证明"
    ],
    "unsupported_claims": [
      "不能仅凭该材料确认商品故障发生时间"
    ]
  },
  "public_text": "这份材料能够对应物流签收事实，但目前仍缺少平台原始导出来源。"
}
```

### 4.1 四项评分

四项评分均由模型直接生成，取值采用 `0.0` 至 `1.0`：

| 分数字段 | 唯一解释字段 | 模型评估含义 |
|---|---|---|
| `authenticity_score` | `authenticity_score_explanation` | 材料来源、可追溯性、异常迹象和真实性风险 |
| `relevance_score` | `relevance_score_explanation` | 材料实际内容与冻结矩阵待证事实的关联程度 |
| `completeness_score` | `completeness_score_explanation` | 材料是否缺页、裁剪、遮挡、缺少上下文或关键字段 |
| `assessment_confidence` | `assessment_confidence_explanation` | 模型对本次评估结论本身的把握程度 |

四项评分相互独立，不求和、不加权、不生成总分。每项评分后必须紧邻一条非空、材料特定的解释字段；解释必须说明该项分数的材料依据和能力边界，不得使用通用占位话术。后端不得重新计算、换算、封顶、压低或覆盖模型分数，也不得改写对应解释。

### 4.2 单一总体风险

删除开放式 `risk_flags[]`。每份材料只生成一个封闭风险等级：

```json
{
  "risk_level": "LOW | MEDIUM | HIGH",
  "risk_explanation": "选择该风险等级的唯一材料特定解释"
}
```

风险等级含义：

| 等级 | 生成规则 |
|---|---|
| `LOW` | 未发现明显高风险异常，现有限制较轻 |
| `MEDIUM` | 存在来源、完整性、时间、清晰度或一致性缺口，但尚不足以判断为高风险 |
| `HIGH` | 存在明显编辑迹象、来源冲突、关键时间异常、严重内容矛盾或其他必须人工确认的重大风险 |

模型必须先完成四项评分，再结合材料来源、形成时间、完整性、可读性、内部一致性、跨材料冲突、事实绑定和实际读取能力选择一个总体风险等级。

模型不能自由创造其他风险标签或风险代码。`risk_explanation` 必须是一条非空、材料特定的综合解释，负责说明四项评分、材料异常和能力边界如何形成总体风险结论，不扩展风险等级枚举。

后端原样读取 `risk_level` 和 `risk_explanation`，不重新判断总体风险，也不检查总体风险是否与四项评分一致。

## 5. 书记官核验反馈保持不变

`EVIDENCE_ASSESSMENT.public_text` 继续作为该材料唯一的书记官核验反馈：

- 由同一次模型调用生成；
- 在 assessment header 完成后逐 Provider delta 输出；
- 同一文本进入聊天框；
- Frame 完成后原样持久化为 `assessment_public_text` 和 `verification_feedback`；
- 材料详情中的“书记官核验反馈”直接展示同一文本；
- 后端和前端不得根据评分、风险或原因码另外拼接第二份反馈。

反馈必须具体说明：

1. 材料实际覆盖了什么；
2. 与哪些冻结事实相关；
3. 现有材料不能证明什么；
4. 主要限制或冲突；
5. 以用户可理解的方式说明能力边界，但不得判定是否进入人工复核，也不得输出复核方向或内部审核指令。

评分字段和总体风险属于结构化 header，不得为了展示评分而推迟前面的 `lead_public_text`。

## 6. 人工复核派生规则

人工复核业务模块必须保留，包括待复核入口、材料复核队列、复核状态、核验关注点展示、原因详情和人工处理结果。取消的只是“由模型另行制定一份人工复核任务/目标/指引”的并行决策链；是否进入人工复核及为什么进入，统一由后端依据同一份模型评分结果机械派生，避免模型任务决定与后端阈值决定形成两个互相冲突的权威。

人工复核只依据四项评分和单一总体风险：

```text
authenticity_score < 0.50
或 relevance_score < 0.50
或 completeness_score < 0.50
或 assessment_confidence < 0.50
或 risk_level == HIGH
→ NEEDS_HUMAN_REVIEW
```

边界值 `0.50` 不触发对应低分原因。`risk_level=HIGH` 即使四项评分全部不低于 `0.50`，仍必须进入人工复核。

### 6.1 固定原因码、文案与模型解释来源

| 条件 | 原因码 | 展示文案 | 人工复核原因详情 |
|---|---|---|---|
| `authenticity_score < 0.50` | `LOW_AUTHENTICITY_SUSPECTED_FORGERY` | 疑似造假：真实性评分低于 50% | 原样使用 `authenticity_score_explanation` |
| `relevance_score < 0.50` | `LOW_RELEVANCE_SCORE` | 关联度低：材料与待证事实的关联性评分低于 50% | 原样使用 `relevance_score_explanation` |
| `completeness_score < 0.50` | `LOW_COMPLETENESS_SCORE` | 完成度低：材料完整性评分偏低 | 原样使用 `completeness_score_explanation` |
| `assessment_confidence < 0.50` | `LOW_ASSESSMENT_CONFIDENCE` | 置信度低：模型对本次核验的把握不足 | 原样使用 `assessment_confidence_explanation` |
| `risk_level == HIGH` | `HIGH_RISK_FLAG` | 模型综合判断该材料为高风险 | 原样使用 `risk_explanation` |

同一材料可同时产生多个原因码。原因顺序固定为真实性、关联性、完整性、核验置信度、总体风险。新链路只生成上表规范名称，不再生成历史别名。

正式人工复核原因保存为：

```json
{
  "requires_human_review": true,
  "reason_details": [
    {
      "code": "LOW_COMPLETENESS_SCORE",
      "label": "完成度低：材料完整性评分偏低",
      "explanation": "材料包含主要物流节点，但没有完整运输轨迹和平台导出上下文。"
    }
  ]
}
```

后端只选择已触发条件对应的模型解释字段并原样保存，不总结、不扩写、不生成审核方向。

### 6.2 最终状态映射

```text
满足任一人工复核条件
→ NEEDS_HUMAN_REVIEW

否则 risk_level == MEDIUM
→ SUSPICIOUS

否则 risk_level == LOW
→ PLAUSIBLE
```

`VERIFIED` 和 `REJECTED` 不由本轮模型评分自动生成，仍属于后续正式审核生命周期状态。

### 6.3 不再生成核验指导任务

模型不再输出以下内容：

- assessment 内的 `human_review.required`；
- 模型生成的人工复核原因码；
- 审核目标 `review_goal/review_target`；
- 审核指引 `instructions/review_instruction`；
- 审核优先级；
- `HUMAN_REVIEW_TASK` Frame；
- 顶层 `human_review_tasks`。

现有输出协议直接删除 `HUMAN_REVIEW_TASK` Frame 类型；其他 Frame 的相对顺序不改变，`EVIDENCE_REQUEST` 后直接生成 `ROOM_READINESS`。

后端只根据四项分数和 `risk_level` 派生：

- `requires_human_review`；
- 固定原因码；
- 固定展示文案；
- 对应模型解释形成的 `reason_details`；
- 最终证据状态。

人工审核入口直接查询 `requires_human_review=true` 的材料核验记录并展示 `reason_details`，不再依赖独立任务对象。人工复核页面继续保留“核验关注点/复核原因”展示模块，但该模块的唯一数据源是 `reason_details`；不再展示或存储模型独立生成的“审核目标”和“审核指引”。页面同时保留材料原件、四项评分、总体风险、模型发现、限制和书记官核验反馈。

这里的 `reason_details` 就是人工复核人员的核验方向：低于阈值的评分项使用该项紧邻的 explanation；总体风险为 `HIGH` 时使用 `risk_explanation`。后端只做条件选择和字段搬运，不总结、不扩写，也不创建另一份指导文本。

模型输出的 `ROOM_READINESS` 不再包含 `human_review_status`；正式 `human_review_status` 由后端根据本轮材料的派生结果形成。

## 7. 模型信任与后端边界

### 7.1 模型及本次 Provider Scheme 负责

- 四项分数的数字类型与 `0.0..1.0` 范围；
- `risk_level` 的 `LOW/MEDIUM/HIGH` 选择；
- `BOUND/UNRELATED/AMBIGUOUS` 结构；
- Frame 顺序、数量与预算；
- 每份当前附件一项 assessment；
- 材料与事实的语义绑定；
- 四项分数各自唯一的 explanation、总体风险唯一的 `risk_explanation`；
- 书记官核验反馈、findings、limitations 和 unsupported claims。

以上约束在调用模型前写入 mode-specific Provider Scheme 和提示词。业务后端不在模型返回后重复执行内容、语义、顺序、数量、预算、assessment 基数或分数范围校验。模型生成顺序作为受信任输出直接消费。

`evidence_id`、`source_unit_id`、`fact_id`、当前角色和附件归属不属于自然语言语义信任范围，继续由后端执行机械权威校验。

### 7.2 后端只负责

- 基础 JSON 解码；
- 确认 `evidence_id` 属于当前批次；
- 确认 `source_unit_id` 属于当前附件；
- 确认 `fact_id` 存在于当前冻结案情矩阵；
- 确认当前角色对引用附件具有正式归属或可见权限；
- 原样读取并投影模型 Scheme；
- 四项 `<0.50` 与 `risk_level=HIGH` 的人工复核派生，并按固定顺序选取对应 explanation 形成 `reason_details`；
- 完整 Frame 持久化；
- 命令幂等、事务原子性、重放和房间状态机；
- 将同一 assessment public text 投影到聊天框和材料详情；
- 将同一 observation fact bindings 投影到正式事实矩阵。

这些 ID、角色和附件检查只允许返回“属于/不属于当前正式权威”，不得检查模型选择该事实的业务理由，不得根据材料正文改绑到其他 `fact_id`。权威检查通过后，关系类型、绑定理由和绑定语义均按模型输出原样投影。

后端不得：

- 重新计算或修改分数；
- 根据其他字段推断或覆盖 `risk_level`；
- 生成开放式风险标签；
- 生成审核目标、审核指引、审核优先级或独立人工复核任务；
- 使用文本相似度修复事实绑定；
- 因低分删除或降级模型事实绑定；
- 用默认值补齐漏评附件；
- 用固定话术或终态 composer 改写书记官反馈。

无法完成基础 JSON 解码或数据库写入时，按技术执行失败处理；不得转换成默认评分、默认风险、默认绑定或默认核验反馈。

## 8. 下游匹配性重构

### 8.1 Python Agent

1. 只替换 `EVIDENCE_ASSESSMENT` 的 header Scheme。
2. 删除 assessment 中枚举式评分字段和开放式 `risk_flags[]`。
3. 增加四项评分、四项唯一 explanation、`risk_level`、`risk_explanation` 和旧版审计说明字段。
4. 更新 Evidence 提示词的四项评分规则、风险选择标准和人工复核条件。
5. 不改变上下文装配顺序、模式顺序或 `lead_public_text`；除删除 `HUMAN_REVIEW_TASK` 外，其他 Frame 类型不变。
6. 不改变 `EVIDENCE_OBSERVATION.fact_bindings`。
7. 从模型输出 Scheme 中删除 assessment `human_review`、`HUMAN_REVIEW_TASK` Frame 和顶层 `human_review_tasks`；原任务段固定为零，不改变其余 Frame 相对顺序。
8. 从模型 `ROOM_READINESS` Scheme 中删除 `human_review_status`，正式状态由后端派生。

### 8.2 Java 正式投影

1. 将 assessment header 原样写入证据核验记录。
2. 将四项评分及其四项 explanation 原样投影到材料目录。
3. 保存 `risk_level` 和 `risk_explanation`。
4. 依据四项 `<0.50` 或 `HIGH` 派生原因码、对应 explanation 的 `reason_details`、`requires_human_review` 和最终状态。
5. 不生成审核目标、审核指引、优先级或独立任务对象；审核入口直接读取需要复核的材料记录。
6. 将 assessment Frame 的同一 `public_text` 保存为 `assessment_public_text` 和 `verification_feedback`。
7. 不保留枚举式 v2 assessment 的兼容读取分支。

### 8.3 前端材料详情

“多维核验结果”恢复显示：

- 真实性百分比；
- 关联性百分比；
- 完整性百分比；
- 核验置信度百分比；
- 总体风险：低、中、高；
- 模型发现；
- 模型限制；
- 总体风险解释；
- 人工复核原因码、固定文案和对应模型解释；
- 书记官核验反馈。

前端不得用枚举默认值覆盖模型评分，也不得在评分字段缺失时伪造“尚未核实”“部分相关”等结论。

人工复核详情继续展示核验关注点，但逐项直接渲染后端派生的 `reason_details`；不再展示模型独立生成的“审核目标”和“审核指引”，也不依赖独立任务对象。

### 8.4 冻结卷宗与庭审输入

Evidence 冻结卷宗和后续庭审 Evidence authority 改为携带：

- 四项模型评分；
- 四项模型评分各自唯一的 explanation；
- 单一总体风险和 `risk_explanation`；
- findings、limitations、unsupported claims；
- 正式人工复核状态、原因码和 `reason_details`；
- 现有 observation graph 和 fact bindings；
- 书记官核验反馈。

庭审只消费正式冻结结果，不重新评分、不重新生成风险、不修改 Evidence 事实绑定。

## 9. 协议与 activation

Assessment 正式字段发生不兼容变化，因此实施时必须提升 Evidence 输出/结果/assessment Schema 版本；`agent-stream.v3` 的传输事件和逐 Delta 语义不变。

切换使用新 activation 和 fresh case：

- 不续跑旧 Evidence checkpoint；
- 不为当前枚举式 assessment 编写兼容转换；
- 不把旧枚举值伪换算成四项分数；
- Python、Java、数据库投影和前端作为同一 release unit 切换。

## 10. 实施顺序

1. 冻结新 assessment Scheme、原因码和前端文案。
2. 修改 Python assessment 合同和 Provider Scheme。
3. 只修改 Evidence 提示词中的评分、逐项 explanation、总体风险、风险 explanation 和人工复核原因规则。
4. 保持现有输出顺序，更新流式 projector 对新 assessment header 的读取。
5. 修改 Java 正式结果、核验记录和材料目录投影，并保留当前批次 ID、Source Unit、冻结 fact 和角色附件归属的机械权威检查。
6. 删除模型人工复核任务输出，修改后端原因派生和状态映射。
7. 修改前端评分、风险、原因详情和反馈展示；保留核验关注点模块并改为直接展示 `reason_details`，删除模型审核目标/指引的数据依赖。
8. 修改 Evidence 冻结卷宗及庭审下游字段投影。
9. 运行决定性局部测试。
10. 发布新 activation，使用固定案例和相同证据材料执行 Evidence UAT。

## 11. 决定性验证

### 11.1 评分与风险

- 四项评分、各自 explanation、`risk_level/risk_explanation` 从模型结果到数据库、API、前端逐字段一致；
- 任一评分 `0.49` 触发对应原因码，并逐字采用该评分的 explanation 作为原因详情；
- 任一评分 `0.50` 不因该项触发人工复核；
- 四项均不低于 `0.50` 且风险为 `HIGH` 时仍进入人工复核；
- 四项均不低于 `0.50` 且风险为 `MEDIUM` 时为 `SUSPICIOUS`，不强制人工复核；
- 四项均不低于 `0.50` 且风险为 `LOW` 时为 `PLAUSIBLE`；
- 不产生开放式 `risk_flags[]` 或历史风险别名。
- `risk_level=HIGH` 时逐字采用 `risk_explanation` 作为 `HIGH_RISK_FLAG` 的原因详情；
- 不生成模型 `human_review`、`HUMAN_REVIEW_TASK`、`human_review_tasks`、审核目标或审核指引。

### 11.2 输出顺序与流式

- `lead_public_text` 仍是第一个模型公开字段；
- 评分 header 不得出现在 `lead_public_text` 前；
- `MATERIAL_REVIEW` Frame 顺序与切换前完全一致；
- 原可选 `HUMAN_REVIEW_TASK` 段固定为零，`EVIDENCE_REQUEST` 后直接生成 `ROOM_READINESS`；
- 顺序、数量、预算和 assessment 基数只由模型规则与 Provider Scheme 保证，不新增后端二次验收；
- assessment `public_text` 在其 header 完成后按真实 Provider delta 输出；
- 不等待完整 JSON 或终态才显示书记官反馈；
- Frame 完成后一次持久化，重放文本与首次输出逐字一致。

### 11.3 绑定与下游

- observation 的模型 `fact_bindings` 原样进入正式事实矩阵；
- 当前批次 `evidence_id`、当前附件 `source_unit_id`、冻结矩阵 `fact_id` 和角色附件归属继续执行机械权威检查；
- 权威检查不使用自然语言、相似度或第二模型，不改变模型关系类型和绑定理由；
- 评分恢复不新增或删除任何事实绑定步骤；
- 材料详情同时显示四项评分、总体风险、模型分析和书记官反馈；
- 人工复核详情的核验关注点模块显示规范原因码、固定文案及对应模型 explanation，不显示模型独立生成的审核目标或审核指引；
- Evidence 冻结结果可被庭审输入直接读取。

## 12. 最终冻结结论

1. 只恢复材料四项评分与相关规则，不重构证据室其他上下文。
2. 风险由模型在 `LOW/MEDIUM/HIGH` 中选择唯一等级，不允许自由生成风险标签。
3. 每项分数必须有一条唯一 explanation，总体风险必须有一条唯一 `risk_explanation`。
4. 后端不重新评分、不重新判定风险，只依据四项 `<0.50` 或 `HIGH` 派生人工复核，并原样采用触发项的 explanation 形成原因详情。
5. 不再让模型生成复核决定、审核目标、审核指引或 `HUMAN_REVIEW_TASK`；人工审核只消费后端派生的原因详情。
6. 人工复核业务模块、核验关注点展示、待办入口、状态流转和人工处理结果继续保留；核验关注点直接来自后端派生的 `reason_details`，删除的是模型生成的独立复核任务，而不是人工复核能力。
7. `LOW_COMPLETENESS_SCORE` 文案固定为“完成度低：材料完整性评分偏低”。
8. `LOW_ASSESSMENT_CONFIDENCE` 文案固定为“置信度低：模型对本次核验的把握不足”。
9. 书记官核验反馈继续由 `EVIDENCE_ASSESSMENT.public_text` 逐 Delta 生成并复用到材料详情。
10. `lead_public_text` 和其余 Frame 相对顺序保持不变，任何实现不得以评分 Scheme 调整为理由延迟前面的公开文本。
11. 事实绑定语义继续完全由模型输出 Scheme 决定；后端只校验 `evidence_id`、`source_unit_id`、`fact_id` 及角色附件归属的正式权威，校验通过后直接投影。
12. 完整 Frame 后持久化，幂等、重放和状态机保持不变。
13. 不兼容的旧 assessment 不做适配，使用新 activation 和 fresh case 验证。
