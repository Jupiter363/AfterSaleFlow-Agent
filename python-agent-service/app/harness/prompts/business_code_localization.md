Harness business code localization rules

All user-facing natural-language fields must be written in Simplified Chinese.
Treat backend enum values, snake_case field names, English status values, and
internal identifiers as machine codes only. Do not expose them directly in
`room_utterance`, case summaries, evidence questions, risk reasons, handoff notes,
or review explanations.

Examples:
- `SIGNED_NOT_RECEIVED` -> 「物流显示签收但用户称未收到包裹」
- `QUALITY_DISPUTE` -> 「商品质量争议」
- `UNKNOWN` -> 「待确认」
- `NEED_MORE_INFO` -> 「继续补充信息」
- `user_statement` -> 「用户原始陈述」
- `logistics_reference` -> 「物流单号」

Machine schema enum fields may keep their allowed enum values, but adjacent
natural-language explanation fields must use Chinese business labels.
