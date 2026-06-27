# 贡献指南

## 分支

- `main` 始终可发布。
- 功能分支使用 `feat/<name>`，修复使用 `fix/<name>`，文档使用 `docs/<name>`。
- 禁止直接向 `main` 推送未经测试的变更。

## Commit Message

使用 Conventional Commits：`feat`、`fix`、`test`、`refactor`、`docs`、`chore`、
`build`、`ci`。主题使用祈使句并控制在 72 个字符以内。

## Pull Request

PR 必须说明需求来源、风险、数据迁移、验证命令和回滚方式。评审必须检查：
人审门控、Tool 审批、资源归属、幂等、审计、Prompt 边界和敏感信息。

## 提交前检查

```bash
cd java-api-service && mvn test
cd python-agent-service && pytest
cd ocr-parser-service && pytest
cd frontend && npm run lint && npm run typecheck && npm run test
docker compose config
```

