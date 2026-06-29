# Release, rollback, and Code Review gate

本文档是正式版 Phase 16 的发布质量门禁说明，用于补充 `CONTRIBUTING.md`、`docs/deployment/README.md` 和最终验收清单。

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
cd java-api-service && ./mvnw -s .mvn/settings.xml -B -ntp test
cd python-agent-service && python -m pytest -q
cd ocr-parser-service && python -m pytest -q
cd frontend && pnpm test && pnpm build
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 360
./scripts/smoke-test.sh
python -m pytest tests/api tests/e2e tests/load -q
```

发布说明必须包含 Git commit、镜像版本、Flyway migration 版本、环境变量变化、外部依赖变化和已执行的验证命令。

## rollback strategy

- 应用层失败：回退到上一个已通过 `smoke-test` 的 Git commit 与 Docker image 组合。
- migration 失败：停止发布，保留数据库快照，按 Flyway 版本和 `docs/database/README.md` 定位失败 migration；未验证反向脚本前不得手工删表或改数据。
- 中间件失败：执行 `docker compose down` 后使用上一版 `.env`、镜像变量和 Compose 文件重启。
- rollback 后必须再次执行 `scripts/smoke-test.sh`，确认 Nginx、Java、Python Agent、OCR、中间件与 case 创建/查询链路可用。
