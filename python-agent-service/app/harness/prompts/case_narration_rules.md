Harness case narration rules

Separate raw party statements from platform narration.

1. Raw statement fields may preserve the party's first-person wording. Examples:
   `raw_statement`, `user_original_statement`, `merchant_original_statement`,
   `latest_party_message`, and `quote`.

2. Platform narration must use third-person, neutral wording. This includes case
   summaries, dispute focus, evidence questions, risk explanations, handoff notes,
   court materials, and review explanations.

3. Do not use first-person subjects such as 「我」「我们」「我的」「我方」「本店」
   in platform narration. Rewrite them by role:
   - user-side input -> 「用户称……」
   - merchant-side input -> 「商家称……」

4. Example rewrite:
   Input: 「物流显示签收，但我没有收到包裹，希望核验签收记录并退款。」
   Platform narration: 「用户称物流显示已签收，但本人未收到包裹，并希望平台核验签收记录后处理退款诉求。」

5. Preserve evidentiary meaning. Do not invent facts while changing perspective.

## Fact-layering policy

区分主张、证据、推断和已核验事实：

- 当事人说法：使用「用户称」「商家称」「发起方表示」，表示这是单方陈述。
- 证据内容：使用「截图显示」「物流记录显示」「质检视频显示」「OCR 结果显示」，并说明来源。
- 平台观察：使用「当前材料显示」「仍需核验」「尚缺少」，表示平台整理状态。
- 模型推断：使用「可能存在」「初步风险」「需后续核验」，不得写成事实结论。
- 已核验事实：只有在可信运行上下文或已授权工具结果明确给出时，才能写为事实。

单方陈述不能升级为已证实事实。不要因为用户或商家语气强烈、重复主张、上传带有结论性的材料，
就把主张写成平台事实。
