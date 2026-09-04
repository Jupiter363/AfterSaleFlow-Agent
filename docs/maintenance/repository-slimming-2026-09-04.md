# 生产仓库瘦身记录（2026-09-04）

本次生产运行时重构以 `main` 的完整快照
`265667ac9224d69a040bfa3324343f2b27cd4f67` 为恢复点，并已将该快照推送到分支
`codex/main-pre-production-runtime-refactor-20260904`。更早的瘦身快照仍保留在
`codex/main-full-backup-20260904-f5cb0686`，但不再作为本轮首选恢复点。

## 从生产分支移除

- 已完成的分阶段实施计划、候选检查点和一次性 Prompt。
- 历史测试报告、冻结工程证据和旧文档归档。
- 已被当前生产契约替代的阶段性 CI 工作流、清单和生成器。
- 只服务于单次本地 UAT/恢复的已失效脚本。

## 明确保留

- Java、Python、OCR、前端运行时代码及当前自动化测试。
- 所有 Flyway/Graph migration、跨服务 schema、replay fixture 与兼容性合同。
- 当前 V4 并行 Graph、Temporal 工作流、Production Runtime、部署、恢复和可观测性资产。
- 当前架构图 `docs/assets/architecture/AfterSaleFlow-Agent-architecture.png`，图片内容未修改。

## 文本同步基线

- 当前功能真值来自已进入 `main` 的 UAT 代码提交
  `10526e58b954498f69bae00ea709f6f9e4981971`；旧的同名本地 UAT 分支停在更早提交，
  不作为文档更新来源。
- Intake 架构文档已从 V3 单体输出改为 V4 exact-three Frame；Evidence 文档已从待切换
  设计改为当前 V2 context / V3 result 实现；总架构和验收清单不再把 Phase 0-8 写成
  未完成的迁移计划。
- 冻结 compatibility matrix、replay fixture 或 ADR 内的旧路径和 Check ID 是其接受时的
  历史身份，不代表该路径仍是 `main` 的活动文档，也不得为消除文字引用而改写协议历史。

备份分支只用于追溯和按文件恢复，不作为继续开发或生产发布分支。
