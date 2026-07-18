# 架构与文档入口

## 当前权威文档

- [当前房间功能基线](../acceptance/current-room-function-baseline.md)：重构前六站旅程、
  权限、Agent 行为、状态推进、持久化事实和 99 个稳定回归编号。
- [Temporal-first Agent Platform Architecture](./temporal-first-agent-platform.md)：面向
  1,000 个并发活跃房间的目标架构与分阶段迁移方案。
- [Temporal-first 生产验证清单](../acceptance/temporal-first-agent-platform-verification-checklist.md)：
  P0/P1/P2 发布门禁、容量、故障注入、安全和灾备证据要求。
- [Hearing Flow V2 合同](../contracts/hearing-flow-v2.md)：当前固定 15 阶段庭审及其对象、
  接口和不可变约束。

当前实现事实与目标架构必须分开阅读：现状以功能基线和代码为准，迁移终态以
Temporal-first 架构为准。任何重构既要通过目标架构门禁，也不能破坏现状回归编号。

## 工程与运行文档

- [API 约定](../api/README.md)
- [数据库说明](../database/README.md)
- [部署说明](../deployment/README.md)
- [发布说明](../release/README.md)
- [Phase 1 Temporal 控制面实施复盘](../runbooks/temporal-first/phase-1-retrospective.md)：
  Phase 2-8 开始前必须复核的问题、根因、防复发规则和阶段执行清单。

## 历史归档

旧三轮庭审、C1-C6、早期验收和一次性模型实测资料已移出 `docs`，统一保存在
[2026-07-17 前置架构历史文档](../../archive/legacy-docs/2026-07-17-pre-temporal/README.md)。
归档资料不再作为实现或发布依据。
