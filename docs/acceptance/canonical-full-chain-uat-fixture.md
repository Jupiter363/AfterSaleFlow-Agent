# 全链路 UAT 统一案例夹具

- 夹具版本：`full-chain-uat-fixture.v1`
- 唯一数据源：`tests/fixtures/canonical_full_chain_uat_case_v1.json`
- 固定场景：商品参数宣传与检测结果不符（空气净化器，人民币 1899 元）
- 固定证据：`air-purifier-performance-test.txt`

所有 Intake → Evidence → Hearing 及后续房间的全链路 UAT 都使用该夹具。案情、双方陈述、诉求、证据类型、claimed fact、文件名、MIME、正文和正文 SHA-256 不得按轮次随机化，也不得根据模拟导入游标换成其他模板。

每轮只允许下列运行身份变化：

- case ID；
- USER / MERCHANT actor ID；
- order、after-sales 等外部引用；
- command、run、attempt、batch、evidence ID；
- 幂等键、nonce、trace、时间戳和 activation 绑定。

失败案件保持只读，不重发已接受的消息、批次或命令。需要重新验证时，用同一夹具创建 fresh case，以便跨 activation、修复版本和房间阶段直接比较首包时间、delta 间隔、公开文本、结构化投影、幂等/重放以及状态机结果。

外部模拟导入的 20 模板轮转属于独立入口 smoke check，不得再作为全链路 UAT 的案情选择器；否则模板游标会破坏跨轮次可比性。
