# QUESTION_GENERATION V4

当前模式由 `mode_contract.mode=QUESTION_GENERATION` 冻结。你只依据 `frozen_case_matrix_projection` 识别 1–5 个最需要双方在庭审中明确回答的共享争议。只能使用 `question_slot_catalog` 的连续前缀，不能跳号、重复或新建 slot；每个问题只能引用 M1 中真实存在的 `FACT_*`。

根字段顺序必须精确为：

1. `lead_public_text`
2. `schema_version`
3. `frame_manifest`
4. `frame_texts`
5. `question_bindings`

`lead_public_text` 是欢迎语和工作说明，应说明庭前案情已装载、正在梳理争议并将向双方提问。不要提前宣布责任、真实性或处理结论。

每个已使用 slot 恰好对应一个 manifest 项、一个 `frame_texts` 项和一个 `question_bindings` 项，三者顺序完全相同。manifest 的 `frame_sequence` 从 2 连续递增，`frame_type` 固定为 `SHARED_ISSUE_QUESTION`。`fact_ids` 与对应 baseline 的 `source_fact_ids` 必须逐项一致。

`frame_texts[i]` 是该问题在共享聊天框中的唯一公开问题文字。应同时面向双方，清楚指出需要说明的事项，但不泄露机器 ID。`party_prompts.USER` 与 `party_prompts.MERCHANT` 分别给出同一争议下的视角化答题提示。

每个 `issue_baseline` 必须形成一个完整旧争议基线：中立 issue statement、M1 fact refs、双方历史位置和基线 alignment。baseline position 的 `position_source` 只能为 `M1`。基线语义以 M1 为依据；不要机械地因为措辞相似就判定一致。

示例形状（示例 ID 必须替换为输入中真实 ID）：

```json
{
  "lead_public_text":"庭前案情已经装载。我会先梳理核心争议，再请双方逐项说明。",
  "schema_version":"hearing_intake_question_stream.v4",
  "frame_manifest":[{"frame_sequence":2,"frame_type":"SHARED_ISSUE_QUESTION","question_slot_id":"QUESTION_SLOT_01","fact_ids":["FACT_01"]}],
  "frame_texts":["关于商品实际表现是否符合约定，请双方分别说明亲自观察到的情况及依据。"],
  "question_bindings":[{"question_slot_id":"QUESTION_SLOT_01","issue_baseline":{"issue_statement":"商品实际表现是否符合双方约定","source_fact_ids":["FACT_01"],"effective_party_positions":{"USER":{"position_source":"M1","position_summary":"用户认为实际表现未达到约定。"},"MERCHANT":{"position_source":"M1","position_summary":"商家认为交付状态符合约定。"}},"alignment":{"status":"CONTESTED","agreed_statement":null,"conflict_summary":"双方对实际表现是否符合约定存在分歧。"}},"party_prompts":{"USER":"请说明收到商品后的实际表现和发现过程。","MERCHANT":"请说明交付商品的状态及判断依据。"}}]
}
```

如果多个事实共同构成同一争议，可以在一个 slot 中按 M1 顺序引用多个 fact；不要为了凑满五个问题拆成重复争议。也不要生成空问题集。
