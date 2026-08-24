# ANSWER_SYNTHESIS V5

当前模式由 `mode_contract.mode=ANSWER_SYNTHESIS` 冻结。`formal_issue_catalog` 和 `formal_question_catalog` 是同一正式 question set 的两种规范视图；不得重新划分、删除或调整旧争议顺序。`party_answer_bundle_catalog` 精确包含 USER、MERCHANT 各一份本轮 `SUBMITTED` 回答，每个旧 issue 各有一个非空 answer unit。

根字段顺序必须精确为：

1. `lead_public_text`
2. `schema_version`
3. `frames`
4. `issue_rebindings`
5. `new_issue_proposals`
6. `matrix_effects`
7. `matrix_summary`

`schema_version` 固定为 `hearing_intake_answer_stream.v5`。`lead_public_text` 先告诉双方陈述已收齐、正在按争议点汇总。`frames` 必须先覆盖全部旧 issue，再按连续 `NEW_ISSUE_SLOT_*` 覆盖本轮真正新增的争议。旧 issue 的 frame type 固定为 `REBIND_ISSUE_SYNTHESIS`；新 issue 固定为 `NEW_ISSUE_SYNTHESIS`；sequence 从 2 连续递增。

每个 frame 是一个不可拆分对象，且只能按 `header`、`public_text` 的顺序生成：

```json
{"header":{"frame_sequence":2,"frame_type":"REBIND_ISSUE_SYNTHESIS","issue_ref":"HEARING_ISSUE_..."},"public_text":"双方已就该争议分别作出当前陈述，现按本轮内容重新归纳分歧。"}
```

每个 header 恰好拥有同一对象内一个非空 `public_text`；禁止拆成 manifest/text 两个数组、跨项配对、合并多个 issue 的文本或省略公开文本。

对每个旧 issue 恰好生成一个 `issue_rebindings`，顺序与 formal issue catalog 相同。USER 和 MERCHANT 都必须绑定各自对该 issue 的当前 answer bundle 和唯一 answer unit：

- `REAFFIRM`：本轮明确重述旧立场，输出完整 current position；
- `REPLACE`：本轮改变、纠正或实质补充旧立场，输出完整 current position；
- `WITHDRAW`：本轮明确撤回，`current_position=null`；
- `NO_POSITION`：本轮明确不主张或无法形成具体立场，`current_position=null`。

旧 issue 的 `effective_party_positions` 由系统直接从上述 `party_bindings.*.current_position` 投影，模型不要重复输出该字段。直接基于双方本轮 current position 重新判断 `AGREED/PARTIALLY_AGREED/CONTESTED/ONE_SIDED/UNRESOLVED`。旧 baseline 只帮助判断 REAFFIRM/REPLACE，不得回填 current position。

每个 `current_alignment`（以及 `matrix_effects` 内每个 `alignment`）都必须显式输出 `status`、`agreed_statement`、`conflict_summary`，并严格使用下列唯一形状；不能省略字段，不能用空字符串代替 `null`：

- `AGREED`：`agreed_statement` 为非空文本，`conflict_summary=null`；
- `PARTIALLY_AGREED`：`agreed_statement` 与 `conflict_summary` 均为非空文本；
- `CONTESTED`：`agreed_statement=null`，`conflict_summary` 为非空文本；
- `ONE_SIDED`：`agreed_statement=null`，`conflict_summary` 为非空文本；
- `UNRESOLVED`：`agreed_statement=null`，`conflict_summary` 为非空文本。

本轮回答首次出现、无法归入旧 issue 的实质争议才使用 `new_issue_proposals`。单方首次提出时固定为 `NEW_UNILATERAL_ISSUE + ONE_SIDED + NOT_PROVIDED + NOT_OPENED`，另一方 position 为 null，不能把沉默解释为同意或反对。只有双方各自当前 answer 都明确涉及同一新争议时才使用 `NEW_SHARED_ISSUE + INDEPENDENTLY_EXERCISED + NOT_OPENED`。

所有 claim、existing fact、new fact 和 relationship effect 必须引用至少一个本轮旧 rebind 或新 issue。发起方 claim effect 只能引用发起方 answer；被发起方 effect 同理。旧 fact 的 ID、category、fact_target、materiality 和 origin 不可改；修正旧命题时新增 fact，并用 `CORRECTS/QUALIFIES/DUPLICATES` 关系连接。

`binding_authority_catalog` 是所有引用字段的唯一取值权威，必须逐字复制，禁止根据字段含义自行缩写、补全或制造别名：

- `issue_rebindings.issue_id` 与旧 frame 的 `header.issue_ref` 必须按 `formal_issue_ids` 原顺序逐项复制；
- 每个旧 issue 的 USER/MERCHANT `answer_bundle_id` 与 `answer_unit_id` 必须从同一项 `answer_binding_catalog.role_bindings` 复制；
- 所有旧 `source_issue_refs` 只能取自 `formal_issue_ids`；新 issue 引用只能使用本轮 `new_issue_proposals` 中实际声明的 `new_issue_slot_id`；
- `existing_fact_effects.fact_id` 只能取自 `existing_fact_ids`；
- `new_fact_effects.new_fact_slot_id` 只能按需使用 `authorized_new_fact_slots` 的连续前缀；
- `fact_refs`、`from_fact_ref`、`to_fact_ref`、`summary_fact_refs` 只能取自 `existing_fact_ids` 加本轮 `new_fact_effects` 中实际声明的 `new_fact_slot_id`；若无需新增事实，只引用已有 fact ID；
- 只有实际生成了对应 `new_issue_proposals` 的新 issue slot，才可被 `source_issue_refs` 或新 frame 引用；
- 只有实际生成了对应 `new_fact_effects` 的新 fact slot，才可被其他 fact 引用字段引用；
- 禁止输出任何不在本次动态目录中的示意别名；结构形状以响应 JSON Schema 为准，ID 值只看本次动态目录。

争点绑定必须先完成“归类与激活”，再生成引用：

1. 能归入 `formal_issue_ids` 中既有争点的本轮陈述，只生成对应 `issue_rebindings`，所有 effect 的 `source_issue_refs` 使用该正式 issue ID。
2. 只有本轮首次出现且无法归入任何既有争点的独立争议，才生成 `new_issue_proposals`，并依次占用 `authorized_new_issue_slots` 的连续前缀。
3. 先完整确定 `new_issue_proposals`，再把其中实际出现的 `new_issue_slot_id` 视为“已激活新争点槽”。本轮有效争点引用集合严格等于 `formal_issue_ids` 加“已激活新争点槽”。
4. 所有 `source_issue_refs` 和新 frame 的 `header.issue_ref` 必须逐字属于上述有效集合。若 `new_issue_proposals` 为空，任何 `NEW_ISSUE_SLOT_*` 都不得出现在其他字段。

事实绑定必须先完成“归类与激活”，再生成引用：

1. 先判断本轮陈述是在更新哪个 `existing_fact_ids`。能归入既有事实命题时只使用 `existing_fact_effects`，不得仅因措辞、数值或立场变化重复创建新事实。
2. 只有首次出现且无法归入既有 `fact_target` 的独立事实命题才使用 `new_fact_effects`，并依次占用 `authorized_new_fact_slots` 连续前缀，禁止生成正式 `FACT_*`。
3. 先完整确定 `new_fact_effects`，再把其中实际出现的 `new_fact_slot_id` 视为“已激活新事实槽”。本轮有效事实引用集合严格等于 `existing_fact_ids` 加“已激活新事实槽”。
4. 所有 issue、relationship 和 summary fact 引用只能逐字属于有效集合。任何新槽在被引用前必须已经且只能声明一次。
5. 如果 `new_fact_effects` 为空，则所有 fact 引用字段只能使用 `existing_fact_ids`，任何 `NEW_FACT_SLOT_*` 都不得出现在其他字段。
6. `matrix_summary.summary_fact_refs` 必须非空、去重，并只列实际支撑摘要与核心冲突的有效事实引用。
7. 关闭最终 JSON 前逐项回查：目录外 issue/fact 引用为零、未激活新槽引用为零、重复 summary refs 为零。

双方都 REAFFIRM 时仍要完整输出 rebind、当前 alignment 和公开 frame；matrix effects 可以为空。不要输出 `PRESERVE`、`CARRY_FORWARD` 或超时回答。`party_updates.*.stance` 由你根据当前回答自主选择响应 Schema 允许的值，应用层不会根据自然语言重新判定该立场。

关闭最终 JSON 前再检查一次唯一映射：`frames` 数量必须等于旧 issue 数量加新 issue proposal 数量；前段 frame headers 与旧 issue IDs 一一同序，后段与实际新 issue slots 一一同序；每个 frame 都同时包含 header 和 public_text。这个自包含映射不得依赖任何另一个数组的相同索引。
