# 本地部署与联调

## 前置条件

- Docker Desktop 已启动，Linux 容器模式可用。
- Docker Compose v2 可用。
- 至少 12 GB 可用内存、25 GB 可用磁盘空间。
- 本地安装 Bash、curl 和 Python 3，用于执行脚本与 smoke test。
- 已获得有效的 DeepSeek API Key。密钥只写入被 Git 忽略的 `.env`。

Windows 默认 Docker Desktop 路径可为
`C:\Program Files\Docker\Docker`，项目本身只依赖 `docker` CLI。

## 首次启动

```bash
cp .env.example .env
./scripts/generate-secrets.sh
```

将 `.env` 中的 `DEEPSEEK_API_KEY` 替换为真实值，然后执行：

```bash
./scripts/dev-up.sh
```

`dev-up.sh` 会依次完成：

1. 补齐本地随机密钥。
2. 校验 DeepSeek Key 未保留占位值。
3. 校验 Compose 配置。
4. 构建 Java、Python、OCR、Frontend 镜像。
5. 启动并等待所有服务健康。
6. 通过 Nginx 执行 smoke test，创建并查询一个测试 Case。

如只启动服务而暂不执行 smoke test：

```bash
RUN_SMOKE_TEST=false ./scripts/dev-up.sh
```

## 服务入口

所有宿主机端口默认只绑定 `127.0.0.1`。

| 服务 | 地址 |
|---|---|
| 统一入口 | `http://localhost:8080` |
| Java health | `http://localhost:18080/actuator/health` |
| Python Agent health | `http://localhost:18000/health` |
| OCR health | `http://localhost:18010/health` |
| Langfuse | `http://localhost:13000` |
| LiteLLM | `http://localhost:14000` |
| MinIO API / Console | `http://localhost:19000` / `http://localhost:19001` |
| Elasticsearch | `http://localhost:19200` |

浏览器前端只访问 Nginx 代理路径：

- Java：`/api`
- Python Agent：`/agent-api`
- OCR：`/ocr-api`
- Langfuse：`/observability`
- LiteLLM：`/llm-admin`

## 镜像覆盖

Compose 默认使用官方镜像名。若网络环境无法访问某个 Registry，可通过
`.env` 或当前 shell 覆盖镜像地址，无需修改 Compose：

```bash
TEMPORAL_IMAGE=your-registry/temporalio/auto-setup:1.25.2 \
LANGFUSE_IMAGE=your-registry/langfuse/langfuse:2.95.11 \
LITELLM_IMAGE=your-registry/berriai/litellm:main-v1.63.14-stable \
./scripts/dev-up.sh
```

覆盖镜像必须与 `.env.example` 中固定版本对应。不得使用未固定的 `latest`。

## 运维命令

```bash
# 查看状态
docker compose ps

# 查看单个服务日志
docker compose logs --tail 200 java-api-service

# 重新执行 smoke test
./scripts/smoke-test.sh

# 停止服务，保留数据卷
./scripts/dev-down.sh

# 明确确认后删除本项目数据卷并重建
CONFIRM_RESET=YES ./scripts/dev-reset.sh
```

单独初始化：

```bash
./scripts/init-db.sh
./scripts/init-es.sh
./scripts/init-minio.sh
```

## 数据与安全

- `.env`、数据库数据卷、MinIO 文件和模型缓存不得提交到 Git。
- PostgreSQL 是业务与审计事实源；Redis 不保存核心业务结果。
- 原始证据与脱敏证据使用不同 MinIO Bucket。
- 只有 Nginx 是统一应用入口；中间件端口仅用于本机排障。
- 日志不得打印 API Key、服务密钥、数据库密码或完整敏感证据。

## 故障排查

- `docker compose config --quiet`：检查变量与 YAML。
- 某服务不健康：运行 `docker compose logs --tail 200 <service>`。
- Docker Hub 镜像不可达：使用上面的镜像覆盖变量，不要在仓库写死第三方镜像代理。
- Docker 数据盘空间不足：先停止构建并清理可再生成的缓存；不要直接删除 VHDX。
- OCR 首次启动较慢：Paddle 模型和依赖较大，可提高
  `STARTUP_TIMEOUT_SECONDS`。
