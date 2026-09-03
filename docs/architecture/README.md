# 架构文档入口

## 当前权威文档

- [Temporal-first Agent Platform Architecture](temporal-first-agent-platform.md)：当前状态
  权威、Temporal/Java/LangGraph 职责、容量与故障语义。
- [接待室 V4 三 Frame 并行 Graph](intake-room-context-and-streaming.md)：当前 Intake
  exact-three 拓扑、上下文、V4 流、Java 合并与局部重试边界。
- [证据室上下文与来源绑定](evidence-room-context-binding-and-token-streaming.md)：当前
  Evidence V2 context、V3 result/frame、图片来源授权与正式化边界。
- [Temporal-first SLO](temporal-first-slo.md)：可用性、延迟、容量和错误预算合同。
- [生产验证清单](../acceptance/temporal-first-agent-platform-verification-checklist.md)：
  P0/P1/P2 发布门禁、容量、故障注入、安全和灾备证据要求。
- [Canonical Full-chain UAT Fixture](../acceptance/canonical-full-chain-uat-fixture.md)：
  可重复六站回归的固定输入、角色和预期结果。
- [当前 UAT 基线](../release/current-uat-baseline.md)：当前 `main` 的版本身份与最新
  fresh-case 浏览器全链路证据。
- [Production Contract Baseline v1](../contracts/README.md)：统一生产合同目录以及
  wire/schema 版本不可重编号的规则。
- [Hearing Flow V2 合同](../contracts/hearing-flow-v2.md)：固定 15 阶段庭审及其
  对象、接口和不可变约束。

## 决策记录

`adr/` 保留仍约束生产实现、版本兼容或恢复边界的架构决策。ADR 记录的是作出决策时的
状态，旧日期、旧协议名和迁移 Check ID 不应被解释为当前运行状态；当前状态以本页列出的
权威文档和代码为准。已完成的阶段准入例外、候选计划和冻结工程证据不再保存在 `main`；完整历史可从备份分支
`codex/main-full-backup-20260904-f5cb0686` 查阅。

ADR 0018 将当前 `main` 定义为 `production-contract-baseline.v1`。该版本只标识当前生产
合同组合，不覆盖各协议已经持久化的 v1/v2/v3/v4 discriminator。

## 工程与运行

- [API 约定](../api/README.md)
- [数据库说明](../database/README.md)
- [部署说明](../deployment/README.md)
- [发布说明](../release/README.md)
- [生产运行手册](../runbooks/README.md)

代码、数据库约束和版本化协议始终优先于说明文字；文档不得扩大模型、Workflow 或
前端的正式写入权限。
