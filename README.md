# AI Native 履约争端审理系统

这是一个面向用户与商家履约争端的 AI Native 审理协作系统。Java 与 PostgreSQL
维护权限、消息、证据、裁决、审核、执行和审计领域账本；Temporal 当前负责持久化
举证窗口，并按目标架构逐步接管房间时间、等待和失败恢复；Python Agent Runtime
Harness 约束 Agent 的身份、上下文、记忆、Skill、模型执行、输出和 Guardrail。

## 核心边界

- 非争端请求只转交外部系统，并在本系统终止。
- 当前用户旅程为接待、证据、`hearing_flow.v2`、裁决草案、人工终审和执行结果。
- 接待和证据使用受治理的单轮 LangGraph；跨回合业务事实仍由 Java 持久化。
- 庭审当前由 Java 持有固定 15 阶段流程，Python 提供七个独立模型操作。
- Agent 不直接退款、补发、驳回或关闭售后。
- 裁决链固定为 Judge V1、Jury Review、Judge V2；V2 仍是非最终草案。
- 平台人工终审承担最终责任，Tool Executor 只执行已批准、哈希绑定且幂等的动作快照。
- 未审批动作不能执行；执行快照、失败和重试均可追溯。
- Evaluation Agent 只离线分析 closed case。
- 当前版本不实现申诉/复审，不引入 Kubernetes、Kafka、MCP 或向量数据库。

## 服务

| 服务 | 职责 | 本地端口 |
|---|---|---:|
| `frontend` | 六站争议旅程、房间交互、审核与结果投影 | 5173 |
| `java-api-service` | 领域账本、REST/SSE、当前庭审流程、Temporal Worker、审批和执行 | 8080 |
| `python-agent-service` | 接待/证据 LangGraph、庭审模型操作、审核 Copilot 和离线评估 | 18000 |
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
- [当前房间功能基线](docs/acceptance/current-room-function-baseline.md)
- [Temporal-first 生产验证清单](docs/acceptance/temporal-first-agent-platform-verification-checklist.md)
- [数据说明](docs/database/README.md)
