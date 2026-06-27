# 订单履约争议裁决系统

这是一个争议裁决驱动、平台人审门控的订单履约协作系统。系统不允许 Agent
直接裁决或执行高风险动作：所有业务路径都必须经过执行方案规划、审批策略、
平台审核和确定性 Tool Executor。

## 服务边界

| 服务 | 职责 | 本地端口 |
|---|---|---:|
| `frontend` | 用户、商家补证和平台审核界面 | 5173 |
| `java-api-service` | 业务事实源、REST API、Temporal Worker、审批和执行 | 18080 |
| `python-agent-service` | Intake、C1-C6、Evaluation Agent | 18000 |
| `ocr-parser-service` | 图片、PDF、Word、Excel 解析 | 18010 |
| `postgresql` | 业务、审计、Temporal、Langfuse、LiteLLM 数据 | 15432 |
| `redis` | 会话、限流、幂等锁和短期上下文 | 16379 |
| `elasticsearch` | 政策、证据和历史案例检索投影 | 19200 |
| `minio` | 原始证据、脱敏证据和导出文件 | 19000/19001 |
| `temporal-server` | 长流程、Signal、超时和重试 | 7233 |
| `langfuse` | Agent Trace | 13000 |
| `litellm-proxy` | 唯一 LLM 网关 | 14000 |
| `nginx` | 统一入口 | 8080 |

## 本地启动

需要 Docker Desktop。Windows 安装路径可以是
`C:\Program Files\Docker\Docker`，但项目只依赖可用的 `docker` CLI。

```bash
cp .env.example .env
./scripts/generate-secrets.sh
# 手工把 DeepSeek key 写入本地 .env；不得提交该文件
./scripts/dev-up.sh
./scripts/smoke-test.sh
```

停止和清理：

```bash
./scripts/dev-down.sh
./scripts/dev-reset.sh
```

## 设计约束

- 模型固定为 `deepseek-v4-flash`，所有调用只经过 LiteLLM。
- Java 是全局状态和审批事实源；Python 不控制全局 Workflow。
- Redis 不保存核心结果、审计或大文件。
- 原始证据和脱敏证据使用不同 MinIO bucket。
- 未审批动作不可执行，人审不可绕过。
- 当前不引入 Kubernetes、Kafka、MCP、向量数据库、Drools、CrewAI 或微服务治理栈。

详细计划见
`docs/superpowers/plans/2026-06-28-formal-order-fulfillment-dispute-system.md`。

