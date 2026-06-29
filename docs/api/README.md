# API 文档

## 统一约定

- 业务 REST 前缀：`/api/v1`
- Agent 前缀：`/agent-api/v1`
- OCR 前缀：`/ocr-api/v1`
- 所有 Java API 使用统一响应 envelope。
- 写操作要求 `Idempotency-Key`。
- 角色身份由 `X-User-Id` 与 `X-Role` 传递；服务间调用还必须携带服务密钥。
- `X-Request-Id` 与 `X-Trace-Id` 用于关联日志和审计。

Java 服务启动后可访问：

- OpenAPI：`http://localhost:18080/v3/api-docs`
- Swagger UI：`http://localhost:18080/swagger-ui.html`

执行 `scripts/generate-openapi.sh` 可生成 OpenAPI 快照。

## 核心资源

- Case：创建、分页查询、详情、路由、Workflow 启动与关闭。
- Evidence：上传、Dossier、时间线、证据矩阵和 OCR 结果。
- Hearing：状态、双方补证、裁决草案。
- Remedy：确定性执行方案。
- Review：审核任务、审核包、确认/修改/驳回/补证/转人工。
- Execution：审批后动作执行和 Action Record。
- Audit：平台角色查询 Case 审计轨迹。
- Evaluation：管理员查询 closed case 离线评估。
