# AI Native 履约争端审理系统

这是一个面向用户与商家履约争端的 AI Native 审理协作系统。Agent Runtime Harness
约束 Agent 的身份、上下文、记忆、Skill、工具、循环、输出与 Guardrail；Java 与
Temporal 维护业务事实和可靠流程；Platform Human Review 承担最终责任；
Tool Executor 只执行经过审批、参数冻结且具备幂等保护的确定性动作。

## 核心边界

- 非争端请求只转交外部系统，并在本系统终止。
- 简易审理和完整审理最终都进入 Remedy Planner、审批、人审和确定性执行。
- Agent 不直接退款、补发、驳回或关闭售后。
- AI 主审官 C1-C6 只生成非最终裁决草案，Remedy Planner 只规划动作。
- 高风险案件按需启动 AI Deliberation Panel，评议报告不能批准案件。
- 未审批动作不能执行；执行快照、失败和重试均可追溯。
- Evaluation Agent 只离线分析 closed case。
- 当前版本不实现申诉/复审，不引入 Kubernetes、Kafka、MCP 或向量数据库。

## 服务

| 服务 | 职责 | 本地端口 |
|---|---|---:|
| `frontend` | Case、补证、证据、审核、执行与审计页面 | 5173 |
| `java-api-service` | 业务事实源、REST API、Temporal Worker、审批和执行 | 8080 |
| `python-agent-service` | Intake、C1-C6、Evaluation Agent | 18000 |
| `ocr-parser-service` | 图片、PDF、Word、Excel 解析 | 18010 |
| `postgresql` | 业务、审计、Temporal、Langfuse、LiteLLM 数据 | 15432 |
| `redis` | 短期状态、缓存和执行锁 | 16379 |
| `elasticsearch` | 政策、证据和历史 Case 检索 | 19200 |
| `minio` | 原始/脱敏证据和解析文件 | 19000/19001 |
| `temporal-server` | 长流程、Signal、超时和重试 | 7233 |
| `langfuse` | Agent Trace | 13000 |
| `litellm-proxy` | 唯一 LLM 网关 | 14000 |
| `nginx` | Docker 全量环境应用入口 | 18080 |

所有端口默认仅绑定 `127.0.0.1`。

## 本地启动

```bash
cp .env.example .env
./scripts/generate-secrets.sh
# 将百炼 DASHSCOPE_API_KEY 写入本地 .env；不要提交该文件
./scripts/dev-up.sh
```

停止服务：

```bash
./scripts/dev-down.sh
```

Windows 下进行 Java/前端快速开发时，保留基础依赖在 Docker 中，让 Spring Boot
直接运行在 `8080`，避免每次修改 Java 后端都重新构建镜像：

```powershell
.\scripts\dev-local.ps1
```

该命令会停止 Docker 中的 `nginx` 和 `java-api-service`，本地启动 Java API 与
Vite，并让 Python Agent/OCR 容器回调宿主机 `8080`。停止本地进程：

```powershell
.\scripts\dev-local.ps1 -Stop
```

本地调试只改变当前开发运行方式，不改变 Docker 的默认服务拓扑。最终部署或需要
恢复全量 Docker 环境时，先停止本地进程，再构建并启动全部容器：

```powershell
.\scripts\dev-local.ps1 -Stop
docker compose up -d --build
```

全量 Docker 环境中，Java API 仍使用宿主机 `8080`，应用统一从 nginx
宿主机 `18080` 进入；Python Agent/OCR 默认通过 `http://java-api-service:8080`
访问容器内 Java API。Docker 前端的 `5173` 是静态服务端口，不承担 API
代理；本地 Vite 开发服务器 `5173` 才会直连并代理 Java API `8080`。

详细说明见：

- [部署文档](docs/deployment/README.md)
- [API 文档](docs/api/README.md)
- [架构说明](docs/architecture/README.md)
- [数据说明](docs/database/README.md)
