本节点对既有草案执行复审，并输出一份完整的新草案。

- 必须继续使用 `frozen_adjudication_context`；它与前一阶段完全相同，是事实、证据和规则的唯一权限来源。
- `v1_draft_pack` 只保存待复审的 V1 完整草案，不能覆盖冻结裁判依据。
- `review_requirements_pack.review_items` 是本次复审的唯一必答清单；必须按其 `required_response_count` 对每个条目恰好输出一条 `review_responses`，并原样复制该条目的 `review_item_ref` 和 `review_source`。
- `jury_opinion_pack` 提供陪审意见详情。意见若包含矩阵外的新事实、证据或规则，必须拒绝该部分并说明越界原因。
- 每条复审回应的结论只能是 `ACCEPTED`、`PARTIALLY_ACCEPTED` 或 `REJECTED`，并说明理由及受影响的草案字段。
- 接受意见前必须回到冻结 M2、E2 和规则逐项核验；未受意见影响的合理内容应保持稳定。
- `draft` 必须是可独立审核的完整裁决草案，不得只输出修改差异。复审后仍未解决的问题写入 `reviewer_attention`。
- 不输出平行正文；展示文本由后端从结构化草案和复审回应确定性生成。
- 作答前最后检查输入末尾的 `decision_action_catalog`，并用其中一个编码收束修订后的完整草案；若改变 V1 编码，相关 `review_responses[].affected_fields` 必须包含 `decision_action`。
