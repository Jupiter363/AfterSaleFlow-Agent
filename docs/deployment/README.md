# 本地部署与联调

补充说明：[Temporal](temporal.md)、[Langfuse](langfuse.md)、
[Production Runtime 隔离 UAT](production-runtime-uat.md)。

当前生产应用身份固定为 `all-rooms.production-runtime.v2` /
`production-runtime-graph.2026-08-18.3` / `production-runtime-checkpoint.v2`，模型固定为
`qwen3.8-flash`（thinking 关闭、strict JSON Schema）。最近的完整浏览器结果见
[当前 UAT 基线](../release/current-uat-baseline.md)。这些应用身份不会授权脚本改变
Temporal、PostgreSQL 或其他核心组件版本。

## 前置条件

- Docker Desktop 已启动，Linux 容器模式可用。
- Docker Compose v2 可用。
- 至少 12 GB 可用内存、25 GB 可用磁盘空间。
- 本地安装 Bash、curl 和 Python 3，用于执行脚本与 smoke test。
- 已获得有效的百炼 DASHSCOPE API Key。密钥只写入被 Git 忽略的 `.env`。

Windows 默认 Docker Desktop 路径可为
`C:\Program Files\Docker\Docker`，项目本身只依赖 `docker` CLI。

## 首次启动

```bash
cp .env.example .env
./tools/generate/generate-secrets.sh
```

将 `.env` 中的 `DASHSCOPE_API_KEY` 替换为真实值，然后执行：

```bash
./tools/dev/dev-up.sh
```

`dev-up.sh` 会依次完成：

1. 补齐本地随机密钥。
2. 校验 DASHSCOPE Key 未保留占位值。
3. 校验 Compose 配置。
4. 构建 Java、Python、OCR、Frontend 镜像。
5. 启动并等待所有服务健康。
6. 通过 Nginx 执行 smoke test，创建并查询一个测试 Case。

如只启动服务而暂不执行 smoke test：

```bash
RUN_SMOKE_TEST=false ./tools/dev/dev-up.sh
```

## Java 进程与 Temporal Worker 拓扑

同一个 Java 镜像通过 Spring Profile 拆成三个独立进程，禁止在 API 进程内顺带启动
Temporal Worker：

| Compose 服务 | Profile | 职责 | 轮询队列 |
|---|---|---|---|
| `java-api-service` | `local,api` | HTTP、鉴权、领域事务、命令接收与恢复投递 | 无 |
| `java-control-worker` | `local,control-worker` | Case/Room Workflow、Timer、领域与投影 Activity | `case-control`、`room-control`、`notification-and-tools` |
| `java-agent-worker` | `local,agent-worker` | 隔离模型与 Agent 执行容量 | `agent-execution` |

CONTROL Worker 的启动依赖只包含 PostgreSQL、Redis、MinIO、Elasticsearch 和 Temporal，
不依赖 Python Agent 或 OCR 健康状态；模型侧故障不得阻断 Timer、取消和案件控制任务。

四条正式协议队列的名称固定，不得按环境、租户或版本动态拼接：

- `case-control`：案件宏观控制、命令顺序与 Continue-As-New。
- `room-control`：房间 Child Workflow、Timer、取消和外部等待。
- `agent-execution`：模型/Agent Activity；拥塞不得占用控制面容量。
- `notification-and-tools`：通知与工具 Activity；其并发和速率独立受限。

队列的并发执行数、poller 数和 Activity 每秒速率由
`app.temporal.worker.*` 配置。Workflow poller 下限为 2；低于下限会在启动时失败，
不会交给 SDK 静默改写。API 和两个 Worker 必须使用同一 Temporal namespace，
但应作为独立 Deployment/HPA/故障域发布。

### EvidenceWindow 兼容队列

`TEMPORAL_TASK_QUEUE` 是现有 EvidenceWindow History 的 legacy compatibility queue，
默认值为 `case-dispute-task-queue`。它不是第五条正式协议队列：

- EvidenceWindow 的新启动和既有 Workflow/Activity replay 继续使用该队列。
- 只有 CONTROL Worker 轮询该队列，并注册旧 Workflow 与 Activity 实现。
- 该值必须与四条正式协议队列不同，否则 CONTROL Worker 启动失败。
- 新 Case/Room 控制面不得向该队列投递。

只有可复核的 Visibility 查询确认该队列上没有 Running、Continued-As-New 或待处理的
EvidenceWindow Workflow/Activity，且 Evidence 房间已经完成新 epoch 的 writer 切换和回滚演练后，
才允许停止兼容 Worker并删除配置。不得以代码已经迁移为理由提前清理旧 Worker。

## Worker 版本发布

`TEMPORAL_WORKER_VERSIONING_MODE` 支持以下模式：

| 模式 | 用途 | 约束 |
|---|---|---|
| `NONE` | 本地开发、bootstrap、无版本路由的测试集群 | 不允许用它做生产滚动混部 |
| `BUILD_ID` | Temporal legacy Build ID routing 兼容路径 | 必须先为每条队列建立 compatibility/default routing；SDK 接口已进入 legacy 状态 |
| `DEPLOYMENT` | 推荐的 Worker Deployment 路径 | Server 必须实际支持 Worker Deployment；Workflow 默认使用 `PINNED` 行为 |

本地 Compose 固定 Temporal `1.25.2` 并默认 `NONE`。不能因为 Java SDK 提供
Worker Deployment API，就假定该本地 Server 支持对应控制面能力。切换到
`BUILD_ID` 或 `DEPLOYMENT` 前，发布流水线必须完成以下步骤：

1. 使用不可变发布标识设置 control/agent 的 deployment name 与 build ID；禁止复用 build ID。
2. 在目标 Temporal Server 上注册新版本，并确认该版本已在角色所属的每条队列产生 poller。
3. 对仍活跃的旧 History 运行 replay/兼容性测试；legacy EvidenceWindow 队列也必须覆盖。
4. 先部署 Worker，再通过 Temporal Operator API/CLI 设置 current/default 或受控 ramp；禁止仅修改环境变量完成 promote。
5. 运行 synthetic Workflow，核对 Workflow/Run/Build、task queue、search attributes 和 probe 返回值。

接待室 V4 浏览器 UAT 使用的是另行授权并验证的 Temporal `1.29.7` 平台；它不会改写
上述 Compose 默认值，也不能作为后续自动升级依据。平台版本差异和升级门禁见
[Temporal 部署边界](temporal.md)。

回滚时先把 current/default/ramp 路由恢复到上一版本，同时保持上一版本 Worker 在线。
`PINNED` Workflow 不会因为路由回滚自动迁移版本；新旧 Worker 都必须保留到 Visibility 查询
证明没有活跃引用。任何 Worker、Build 或 Graph 清理都必须先通过同等的活跃引用与回滚门禁。

## Temporal 恢复任务

`APP_ORCHESTRATION_NEW_EPOCH_MODE` 只允许 `LEGACY`、`SHADOW` 或 `TEMPORAL`，默认
`LEGACY`。Java 只在创建新 room epoch 时读取该 selector，并把结果持久化到
`case_room_epoch.writer_mode`；已有 epoch、Temporal Workflow replay 和运行中的 Graph 不得
重新读取动态值。回滚 selector 只影响后续新 epoch，不能把活跃 TEMPORAL epoch 原地交还旧 writer。

Control Worker 承载两个默认关闭的有界恢复任务：

- `APP_ORCHESTRATION_DOMAIN_EVENT_RECOVERY_ENABLED` 从 Java 的
  `case_timeline_event` 持久账本补投 Case Workflow Signal。投递是 at-least-once，
  Workflow 按 sequence 和 payload identity 去重；不得用进程内 after-commit 回调替代该 detector。
- `APP_ORCHESTRATION_PROJECTION_RECONCILIATION_ENABLED` 扫描 SHADOW/TEMPORAL epoch。
  Query 结果只能提供观测，不能单独授权 projection 修复。生产 reader 必须同时核对
  bootstrap commitment、first/current Run 的 History chain，以及 History 中的
  `case_process_authority_checkpoint_v1` memo。当前实现仅允许尚未发生业务推进、且上述证据
  完整一致的 bootstrap checkpoint 返回 `Verified`；其他不完整状态继续 fail-closed 为
  `SOURCE_INCOMPLETE`。SHADOW 始终只记录 drift，只有 TEMPORAL 的 `Verified` 结果可进入受 fencing
  保护的修复事务；History 不可用时不得降级使用 Query。

两个任务都限制单批大小并拒绝同 JVM 重入；多副本依靠 Signal 幂等和数据库 fencing 保持安全。
只有 PostgreSQL、replay、Worker 恢复和 reconciliation 证据绑定同一发布版本并全部通过后，
才允许在 control worker 开启。回滚时先关闭这两个入口，保留账本、History 和审计记录。

## 服务入口

所有宿主机端口默认只绑定 `127.0.0.1`。

| 服务 | 地址 |
|---|---|
| 前端静态容器（不代理 API） | `http://localhost:5173` |
| Java health | `http://localhost:8080/actuator/health` |
| Python Agent health | `http://localhost:18000/health` |
| OCR health | `http://localhost:18010/health` |
| Docker 应用统一入口 | `http://localhost:18080` |
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

本地 Vite 开发服务器使用 `5173`，并将 `/api` 直接代理到 Java API `8080`。
Docker 全量部署时必须通过 Nginx `18080` 使用完整应用；Docker 前端容器的
`5173` 仅提供静态资源，不承担 `/api` 代理。

## 镜像覆盖

Compose 默认使用官方镜像名。若网络环境无法访问某个 Registry，可通过
`.env` 或当前 shell 覆盖镜像地址，无需修改 Compose：

```bash
TEMPORAL_IMAGE=your-registry/temporalio/auto-setup:1.25.2 \
LANGFUSE_IMAGE=your-registry/langfuse/langfuse:2.95.11 \
LITELLM_IMAGE=your-registry/berriai/litellm:main-v1.63.14-stable \
./tools/dev/dev-up.sh
```

覆盖镜像必须与 `.env.example` 中固定版本对应。不得使用未固定的 `latest`。

## 运维命令

```bash
# 查看状态
docker compose ps

# 查看单个服务日志
docker compose logs --tail 200 java-api-service

# 重新执行 smoke test
./tools/verify/smoke-test.sh

# 停止服务，保留数据卷
./tools/dev/dev-down.sh

# 明确确认后删除本项目数据卷并重建
CONFIRM_RESET=YES ./tools/dev/dev-reset.sh
```

单独初始化：

```bash
./tools/dev/init-db.sh
./tools/dev/init-es.sh
./tools/dev/init-minio.sh
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
