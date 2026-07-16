# 2026-07-17 前置架构历史文档

- 归档日期：2026-07-17
- 来源基线：`dc6846fbf75455361bc88b132306dcca65eb0598`
- 状态：只读历史资料，不再作为实现、重构或发布依据

本目录保存被当前房间功能基线、`hearing_flow.v2` 和 Temporal-first 目标架构取代的
阶段性设计、验收与模型实测记录。文件保留原来的 `docs/...` 子目录结构，便于追溯
历史讨论和 Git 变更。

## 归档内容

| 原目录或文件 | 数量 | 归档原因 |
| --- | ---: | --- |
| `docs/codex` | 6 | 一次性模型调用、上下文和验收证据，包含旧三轮庭审语义 |
| `docs/acceptance/full-chain-audit` | 7 | 2026-07-05 早期全链路验收，已被当前功能基线替代 |
| `docs/acceptance/frontend-long-text-layout-audit-2026-07-10.md` | 1 | 布局整改前的问题快照，当前 UI 已有新的固定外壳回归约束 |
| `docs/test-reports/2026-07-15-hearing-e2e-issues.md` | 1 | 旧三轮庭审 E2E 问题记录，现行实现已切换到 `hearing_flow.v2` |
| `docs/architecture/final-module-map.md` | 1 | C1-C6 与旧路由所有权图，已被 Temporal-first 架构取代 |

合计 16 份文件。

用户主动删除的 `docs/design` 和 `docs/superpowers` 不在本归档中，也不会从 Git 历史
自动恢复。

## 当前权威入口

- [当前房间功能基线](../../../docs/acceptance/current-room-function-baseline.md)
- [Temporal-first 目标架构](../../../docs/architecture/temporal-first-agent-platform.md)
- [Temporal-first 生产验证清单](../../../docs/acceptance/temporal-first-agent-platform-verification-checklist.md)
- [Hearing Flow V2 合同](../../../docs/contracts/hearing-flow-v2.md)

归档内容只能用于解释历史决策。若与当前代码或上述权威文档冲突，以当前代码、合同和
功能基线为准。
