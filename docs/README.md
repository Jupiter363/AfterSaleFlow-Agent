# 项目文档

`docs/` 是当前生产基线的统一文档入口。仓库根目录只保留 GitHub 入口
`README.md`、Agent 约束文件和构建配置；运行时目录不再散放说明文档。

## 导航

- [架构](architecture/README.md)：状态权威、Temporal/LangGraph 边界、ADR 与 SLO。
- [验收](acceptance/temporal-first-agent-platform-verification-checklist.md)：当前发布门禁；
  [Canonical UAT 夹具](acceptance/canonical-full-chain-uat-fixture.md)固定全链路输入。
- [接口](api/README.md)：公共 API、身份、幂等与 SSE 约定。
- [业务合同](contracts/README.md)：统一的 Production Contract Baseline v1、具体协议版本和变更规则。
- [数据](database/README.md)：数据库、迁移和存储边界。
- [部署](deployment/README.md)：Compose、Temporal、Langfuse 与隔离 E2E。
- [开发](development/contributing.md)：贡献流程与代码规范。
- [前端](frontend/README.md)：模块职责和前端安全边界。
- [安全](security/security-policy.md)：漏洞报告、核心安全规则与 Prompt 安全加固记录。
- [测试](testing/smoke-test-cases.md)：冒烟测试和 Temporal History 夹具说明。
- [发布](release/README.md)：质量门禁和回滚策略。
- [当前 UAT 基线](release/current-uat-baseline.md)：当前 `main` 对应的 Graph、模型、迁移与浏览器证据。
- [运行手册](runbooks/README.md)：仍在使用的告警、恢复、轮换和灾备流程。

## 文档保留规则

- 只保留与当前生产代码、兼容性、发布或恢复直接相关的文档。
- 已完成的阶段计划、候选证据、一次性调查和历史测试输出不进入 `main`。
- 运行时代码所需的 Markdown Prompt 不属于文档资产，继续与 Python 源码共置。
- 数据库 migration、协议 schema、replay fixture 和 ADR 即使版本较早，也可能是兼容性
  边界，不按日期删除。
- 历史完整快照可从备份分支
  `codex/main-full-backup-20260904-f5cb0686` 查阅。
