# 订单履约争议裁决系统

这是一个争议裁决驱动、平台人审门控的订单履约协作系统。Agent 负责理解、归纳、
证据分析和生成非最终草案；Java Workflow、审批策略、平台审核员与 Tool Executor
共同保证高风险动作不可绕过人工确认。

## 核心边界

- 所有业务路径最终进入 Remedy Planner、审批、人审和确定性执行。
- Agent 不直接退款、补发、驳回或关闭售后。
- C 层只生成非最终裁决草案，D 层只规划动作。
- 未审批动作不能执行；执行快照、失败和重试均可追溯。
- Evaluation Agent 只离线分析 closed case。
- 当前版本不实现申诉/复审，不引入 Kubernetes、Kafka、MCP 或向量数据库。

## 服务

| 服务 | 职责 | 本地端口 |
|---|---|---:|
| `frontend` | Case、补证、证据、审核、执行与审计页面 | 5173 |
| `java-api-service` | 业务事实源、REST API、Temporal Worker、审批和执行 | 18080 |
| `python-agent-service` | Intake、C1-C6、Evaluation Agent | 18000 |
| `ocr-parser-service` | 图片、PDF、Word、Excel 解析 | 18010 |
| `postgresql` | 业务、审计、Temporal、Langfuse、LiteLLM 数据 | 15432 |
| `redis` | 短期状态、缓存和执行锁 | 16379 |
| `elasticsearch` | 政策、证据和历史 Case 检索 | 19200 |
| `minio` | 原始/脱敏证据和解析文件 | 19000/19001 |
| `temporal-server` | 长流程、Signal、超时和重试 | 7233 |
| `langfuse` | Agent Trace | 13000 |
| `litellm-proxy` | 唯一 LLM 网关 | 14000 |
| `nginx` | 统一入口 | 8080 |

所有端口默认仅绑定 `127.0.0.1`。

## 本地启动

```bash
cp .env.example .env
./scripts/generate-secrets.sh
# 将 DeepSeek Key 写入本地 .env；不要提交该文件
./scripts/dev-up.sh
```

停止服务：

```bash
./scripts/dev-down.sh
```

详细说明见：

- [部署文档](docs/deployment/README.md)
- [API 文档](docs/api/README.md)
- [架构说明](docs/architecture/README.md)
- [数据说明](docs/database/README.md)
