# ANSWER_SYNTHESIS V4

当前模式由 `mode_contract.mode=ANSWER_SYNTHESIS` 冻结。`formal_issue_catalog` 和 `formal_question_catalog` 是同一正式 question set 的两种规范视图；不得重新划分、删除或调整旧争议顺序。`party_answer_bundle_catalog` 精确包含 USER、MERCHANT 各一份本轮 `SUBMITTED` 回答，每个旧 issue 各有一个非空 answer unit。

根字段顺序必须精确为：

1. `lead_public_text`
2. `schema_version`
3. `frame_manifest`
4. `frame_texts`
5. `issue_rebindings`
6. `new_issue_proposals`
7. `matrix_effects`
8. `matrix_summary`

`lead_public_text` 先告诉双方陈述已收齐、正在按争议点汇总。随后 manifest 必须先覆盖全部旧 issue，再按连续 `NEW_ISSUE_SLOT_*` 覆盖本轮真正新增的争议。旧 issue 的 frame type 固定为 `REBIND_ISSUE_SYNTHESIS`；新 issue 固定为 `NEW_ISSUE_SYNTHESIS`；sequence 从 2 连续递增。每项恰好对应一个公开 `frame_texts` 文本。

对每个旧 issue 恰好生成一个 `issue_rebindings`，顺序与 formal issue catalog 相同。USER 和 MERCHANT 都必须绑定各自对该 issue 的当前 answer bundle 和唯一 answer unit：

- `REAFFIRM`：本轮明确重述旧立场，输出完整 current position；
- `REPLACE`：本轮改变、纠正或实质补充旧立场，输出完整 current position；
- `WITHDRAW`：本轮明确撤回，`current_position=null`；
- `NO_POSITION`：本轮明确不主张或无法形成具体立场，`current_position=null`。

旧 issue 的 `effective_party_positions` 由系统直接从上述 `party_bindings.*.current_position` 投影，模型不要重复输出该字段。请直接基于双方本轮 `current_position` 重新判断 `AGREED/PARTIALLY_AGREED/CONTESTED/ONE_SIDED/UNRESOLVED`。旧 baseline 只帮助判断 REAFFIRM/REPLACE，不得回填 current position。

每个 `current_alignment`（以及 `matrix_effects` 内每个 `alignment`）都必须显式输出 `status`、`agreed_statement`、`conflict_summary` 三个字段，并严格使用下列唯一形状；不能省略字段，不能用空字符串代替 `null`：

- `AGREED`：`agreed_statement` 为非空文本，`conflict_summary=null`；
- `PARTIALLY_AGREED`：`agreed_statement` 与 `conflict_summary` 均为非空文本；
- `CONTESTED`：`agreed_statement=null`，`conflict_summary` 为非空文本；
- `ONE_SIDED`：`agreed_statement=null`，`conflict_summary` 为非空文本；
- `UNRESOLVED`：`agreed_statement=null`，`conflict_summary` 为非空文本。

本轮回答首次出现、无法归入旧 issue 的实质争议才使用 `new_issue_proposals`。单方首次提出时固定为 `NEW_UNILATERAL_ISSUE + ONE_SIDED + NOT_PROVIDED + NOT_OPENED`，另一方 position 为 null，不能把沉默解释为同意或反对。只有双方各自当前 answer 都明确涉及同一新争议时才使用 `NEW_SHARED_ISSUE + INDEPENDENTLY_EXERCISED + NOT_OPENED`。

所有 claim、existing fact、new fact 和 relationship effect 必须引用至少一个本轮旧 rebind 或新 issue。发起方 claim effect 只能引用发起方 answer；被发起方 effect 同理。旧 fact 的 ID、category、fact_target、materiality 和 origin 不可改；修正旧命题时新增 fact，并用 `CORRECTS/QUALIFIES/DUPLICATES` 关系连接。

`binding_authority_catalog` 是所有引用字段的唯一取值权威，必须逐字复制，禁止根据字段含义自行缩写、补全或制造别名：

- `issue_rebindings.issue_id`、`frame_manifest` 的旧 `issue_ref` 必须按 `formal_issue_ids` 原顺序逐项复制；
- 每个旧 issue 的 USER/MERCHANT `answer_bundle_id` 与 `answer_unit_id` 必须从同一项 `answer_binding_catalog.role_bindings` 复制；
- 所有 `source_issue_refs` 以及新 issue 的 `issue_ref` 只能取自 `allowed_issue_refs`；
- `existing_fact_effects.fact_id` 只能取自 `existing_fact_ids`；
- `new_fact_effects.new_fact_slot_id` 只能按需使用 `authorized_new_fact_slots` 的连续前缀；
- `fact_refs`、`from_fact_ref`、`to_fact_ref`、`summary_fact_refs` 只能取自 `allowed_fact_refs`；若无需新增事实，优先引用已有 fact ID；
- 只有实际生成了对应 `new_issue_proposals` 的新 issue slot，才可被 `source_issue_refs` 引用；
- 只有实际生成了对应 `new_fact_effects` 的新 fact slot，才可被其他 fact 引用字段引用；
- 禁止输出任何不在本次动态目录中的示意别名；结构形状以响应 JSON Schema 为准，ID 值只看本次动态目录。

事实绑定必须先完成“归类与激活”，再生成引用。`authorized_new_fact_slots` 和 `allowed_fact_refs` 只表示本轮可用的命名空间上限，不表示其中每个新槽已经成为事实。生成最终 JSON 前，在内部按以下顺序完成一致性检查；不要把检查过程或集合名称输出给用户：

1. 先逐项判断本轮陈述是在更新哪个 `existing_fact_ids`。能够归入既有事实命题时，只使用 `existing_fact_effects`；不得仅因措辞、数值或立场发生变化就重复创建新事实。
2. 只有本轮首次出现且无法归入任何既有 `fact_target` 的独立事实命题，才使用 `new_fact_effects`。新事实只能依次占用 `authorized_new_fact_slots` 的连续前缀，禁止自行生成正式 `FACT_*` ID。
3. 先完整确定 `matrix_effects.new_fact_effects`，再把其中实际出现的 `new_fact_slot_id` 视为“已激活新事实槽”。本轮有效事实引用集合严格等于 `existing_fact_ids` 加“已激活新事实槽”，不是整个 `allowed_fact_refs`。
4. `new_issue_proposals.fact_refs`、`relationship_effects.from_fact_ref`、`relationship_effects.to_fact_ref` 和 `matrix_summary.summary_fact_refs` 中的每个值，都必须逐字属于上述有效事实引用集合。任何新事实槽在被引用前，必须已经且只能在 `new_fact_effects` 中声明一次。
5. 如果 `new_fact_effects` 为空，则所有 fact 引用字段只能使用 `existing_fact_ids`，任何 `NEW_FACT_SLOT_*` 都不得出现在其他字段。
6. `matrix_summary.summary_fact_refs` 必须非空、去重，并只列出实际支撑 `summary_text` 与 `core_conflict` 的既有事实或已激活新事实；不得根据事实含义创造别名、缩写或猜测 ID。
7. 在关闭最终 JSON 前，逐项回查所有 fact 引用：目录外值为零、未激活新槽引用为零、重复 `summary_fact_refs` 为零。若发现任一项，必须在当前输出中自行改正后再结束生成。

双方都 REAFFIRM 时仍要完整输出 rebind、当前 alignment 和公开 frame；matrix effects 可以为空。不要输出 `PRESERVE`、`CARRY_FORWARD`、`NOT_ADDRESSED` 或超时回答。
