# API 约定

系统只公开三个未版本化资源根：

- `/api/disputes`：争议订单、房间消息、证据、庭审、裁决与事件流。
- `/api/notifications`：平台信箱、未读数与已读状态。
- `/api/reviews`：平台审核任务、审核包、规则与评估指标。

`/api/v1`、`/agent-api` 和 `/ocr-api` 已停止公开。服务间接口统一放在
`/internal/**`，必须使用 `X-Service-Identity`，并且不会由 Nginx 暴露。

## 身份与响应

- 当事人请求使用 `X-User-Id` 与 `X-Role`。
- 服务请求使用 `X-Service-Identity`；需要共享密钥的适配器还必须携带对应
  `X-Service-Secret`。
- 写操作使用 `Idempotency-Key`。
- Java API 使用统一响应 envelope，并返回 `request_id` 与 `trace_id`。
- AI 结果始终是非最终建议；最终裁决必须由平台审核员确认。

## SSE 断线续传

`GET /api/disputes/{caseId}/events` 返回 `text/event-stream`。客户端重连时使用
`Last-Event-ID`，服务端从该序号之后重放可见事件。Nginx 对此路径关闭缓冲和缓存，
读取超时为四小时。

## OpenAPI

- OpenAPI：`http://localhost:18080/v3/api-docs`
- Swagger UI：`http://localhost:18080/swagger-ui.html`

执行 `scripts/generate-openapi.sh` 可生成 OpenAPI 快照。
