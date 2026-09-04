# Release、rollback 与 Code Review gate

本文档是当前生产分支的发布质量门禁，配合[贡献指南](../development/contributing.md)、
[部署说明](../deployment/README.md)和[生产验证清单](../acceptance/temporal-first-agent-platform-verification-checklist.md)使用。

当前业务浏览器证据见[当前 UAT 基线](current-uat-baseline.md)，首次生产 clean break 与目录
映射见[Production Runtime 生产重构](production-runtime-refactor-2026-09-04.md)。UAT 通过不是
跳过本页 release gate、复用旧持久状态、自动打开生产路由或升级核心组件的授权。

## Code Review Checklist

- 确认没有绕过 Platform Human Review、Approval Policy Engine 或 Tool Executor 审批校验。
- 确认 Agent 只输出结构化分析和裁决草案，不直接裁决、不直接执行退款、补发或关闭售后。
- 确认新增 API 保持统一 `ApiResponse`、错误码、鉴权、幂等键和审计记录。
- 确认数据库 migration、Docker Compose、脚本、环境变量和文档与代码同步更新。
- 确认 `smoke-test`、单元测试、集成测试、API/E2E/load smoke 均有对应结果或明确说明。

## release gate

release 前必须完成以下命令，并把输出保存到发布记录或 PR 描述：

```bash
python -m pytest tests/static -q
cd apps/domain-service && ./mvnw -s .mvn/settings.xml -B -ntp test
cd apps/agent-runtime && python -m pytest -q
cd apps/ocr-parser && python -m pytest -q
cd apps/web && pnpm test && pnpm build
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 360
./tools/verify/smoke-test.sh
python -m pytest tests/integration/api tests/e2e tests/performance -q
```

发布说明必须包含 Git commit、镜像版本、Flyway migration 版本、环境变量变化、外部依赖变化和已执行的验证命令。

## rollback strategy

- 应用层失败：回退到上一个已通过 `smoke-test` 的 Git commit 与 Docker image 组合。
- migration 失败：停止发布，保留数据库快照，按 Flyway 版本和 `docs/database/README.md` 定位失败 migration；未验证反向脚本前不得手工删表或改数据。
- 中间件失败：先停止应用写入并保留容器、volume、schema 和 image digest 证据；没有单独授权
  与恢复演练时，不升级、降级、重建或重定向 Temporal/PostgreSQL 等核心组件。
- Temporal schema 已前向迁移时，不得只切回旧 server image；必须恢复匹配版本的一致数据库
  快照，或按已批准方案修复前进。禁止 `docker compose down -v` 和手工修改 schema 版本。
- rollback 后必须再次执行 `tools/verify/smoke-test.sh`，确认 Nginx、Java、Python Agent、OCR、中间件与 case 创建/查询链路可用。
