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
