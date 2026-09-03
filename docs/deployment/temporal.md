# Temporal 部署边界

本页说明仓库默认配置与已验证 UAT 平台之间的区别。任何启动命令都不得据此隐式升级、
重建或迁移 Temporal。

## 仓库默认值

- 本地 Compose 使用 `temporalio/auto-setup:1.25.2`。
- namespace 为 `default`。
- `TEMPORAL_WORKER_VERSIONING_MODE` 默认 `NONE`。
- 业务协议队列为 `case-control`、`room-control`、`agent-execution` 和
  `notification-and-tools`。
- `TEMPORAL_TASK_QUEUE` 只保存历史 EvidenceWindow 的兼容队列身份，不是新的
  Case/Room 主队列。

API 进程不注册 Worker；CONTROL 与 AGENT Worker 必须以独立角色启动。队列名、namespace、
build/deployment identity 和 payload codec 配置在同一 release 内必须一致。

## UAT 平台事实

V4 全链路 UAT 曾在经用户单独授权升级并验证的 Temporal Server `1.29.7` 上完成，
包括 deployment-pinned Workflow 与 Activity 路由。该事实只证明对应 UAT 环境的兼容性：

- 不会改变 Compose 中的默认镜像；
- 不授权启动脚本升级任何集群；
- 不允许把 1.29.7 的 schema 直接交给旧 server；
- 不替代 production replay、备份恢复、容量和路由门禁。

## 版本变更

Temporal Server、persistence schema、dynamic config、namespace 或 Worker Deployment 路由
都属于核心平台变更。执行前必须取得明确授权，并满足：

1. 记录当前 server image digest、schema、namespace、history sentinel 与路由状态；
2. 冻结应用写入并取得 core/visibility 一致备份和恢复演练证据；
3. 按官方支持顺序逐 minor 迁移 schema，先 schema 后 server；
4. 每一步验证 cluster health、历史可读性、Workflow/Activity poller 和 replay；
5. 保留匹配旧 History 的 Worker，不在活跃 execution 上热换不兼容 build；
6. 回滚恢复匹配版本的数据库快照，不手工修改 `schema_version`。

不得用 `docker compose down -v`、删除 volume、旧 auto-setup 隐式迁移或 legacy
Build-ID 重定向掩盖失败。

## Worker versioning

| 模式 | 用途 |
| --- | --- |
| `NONE` | 默认本地开发和无版本路由测试 |
| `BUILD_ID` | 旧 History 的 legacy compatibility routing |
| `DEPLOYMENT` | 受支持 server 上的 Worker Deployment；显式验证 Workflow 与 Activity poller |

`PINNED` execution 必须由精确 deployment/build 的 poller消费。设置 Current 或 Ramping
是全 deployment 路由变更，不能作为单案恢复捷径。Recovery worker 必须使用显式注册
scope，避免取得不属于其权限的共享队列任务。

## 观测与加密

Search Attributes、OpenTelemetry 和 payload codec 的完整配置见
[Temporal observability and payload codec](temporal-observability-and-payload-codec.md)。
Search Attribute 注册和加密 key rotation 都是显式运维操作，不是应用 readiness 的启动
副作用。
