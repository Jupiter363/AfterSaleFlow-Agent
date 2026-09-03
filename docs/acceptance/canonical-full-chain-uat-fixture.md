# 全链路 UAT Canonical 回归夹具

- 夹具版本：`full-chain-uat-fixture.v1`
- 唯一数据源：`tests/fixtures/canonical_full_chain_uat_case_v1.json`
- 固定场景：商品参数宣传与检测结果不符（空气净化器，人民币 1899 元）
- 固定证据：`air-purifier-performance-test.txt`

所有需要跨版本比较的自动化/可重复 Intake → Evidence → Hearing 及后续房间回归都使用该
夹具。案情、双方陈述、诉求、证据类型、claimed fact、文件名、MIME、正文和正文
SHA-256 不得按轮次随机化，也不得根据模拟导入游标换成其他模板。

浏览器探索或多模态补充 UAT 可以从真实前端表单创建另一份 fresh case，并上传新的授权
图片/文档；这种结果必须记录独立 case ID、输入与 hash，且不能替代 canonical fixture 的
可比性门禁。当前 `_11` 浏览器通过记录见[当前 UAT 基线](../release/current-uat-baseline.md)。

案件必须通过 `POST /api/disputes/import/simulate` 的显式
`fixture_id=air-purifier-specification-mismatch-v1` 导入。该 discriminator 是固定夹具的唯一选择权威：未知值必须拒绝；同一幂等键必须重放同一案件；固定夹具导入不得读取或推进 20 模板轮转游标。

每轮只允许下列运行身份变化：

- case ID；
- USER / MERCHANT actor ID；
- order、after-sales 等外部引用；
- command、run、attempt、batch、evidence ID；
- 幂等键、nonce、trace、时间戳和 activation 绑定。

失败案件保持只读，不重发已接受的消息、批次或命令。需要重新验证时，用同一夹具创建 fresh case，以便跨 activation、修复版本和房间阶段直接比较首包时间、delta 间隔、公开文本、结构化投影、幂等/重放以及状态机结果。

未携带 `fixture_id` 的外部模拟导入仍执行 20 模板轮转，只属于独立入口 smoke check，
不得作为 canonical 回归的案情选择器；否则模板游标会破坏跨轮次可比性。
