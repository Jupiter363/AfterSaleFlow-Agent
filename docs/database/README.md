# 数据与存储

## 数据权威

- PostgreSQL Domain DB：案件、命令、房间、证据、审核、执行、审计和正式投影的事实源。
- PostgreSQL Graph DB：LangGraph checkpoint、命令账本、lease/fencing、版本注册和认知运行状态。
- Temporal persistence：Workflow History、Timer、Signal、Update 与 Activity 调度状态；不保存大型领域正文。
- Redis：幂等执行锁、短期状态和缓存；不保存核心裁决结果。
- MinIO：原始证据、脱敏证据、OCR 临时文件、政策文件和导出文件。
- Elasticsearch：政策、证据和历史 Case 的可重建检索投影。

Java 是 Domain DB 的唯一正式业务写入方。Python/LangGraph 只写 Graph DB，并通过版本化
proposal/receipt 交由 Java Finalizer 验收；Temporal 负责编排顺序，不直接成为业务事实源。

## 当前迁移基线

| 数据面 | 迁移目录 | 当前上限 |
| --- | --- | --- |
| Java / Domain | `apps/domain-service/src/main/resources/db/migration` | `V094__target_e2e_graph_patch_release_identity.sql` |
| Python / Graph | `apps/agent-runtime/migrations/graph` | `G017_fanout_command_terminalization_authority.sql` |

两组 migration 都是追加式兼容历史：已发布文件不得重命名、改写或复用版本号。Java 使用
`ddl-auto=validate`，禁止 Hibernate 自动改表；Graph runtime 必须先验证 migration 与
GraphRegistry/checkpoint 身份，再接收命令。

Temporal core/visibility schema 不由应用 Flyway 或 Graph migration 管理。Temporal Server
及其 schema 的任何升级都需要单独授权、同版本工具、备份恢复证据和逐版本验收，应用启动
不得隐式执行。

## 存储边界

- 原始证据与脱敏证据必须使用不同 Bucket，并以 actor/case/room/source hash 授权读取。
- 正式消息、证据、工件、审核决定和执行记录是 append-only 或受 revision/fence CAS 保护的事实。
- 搜索索引和 Redis 数据必须能从 PostgreSQL/对象账本重建，不能反向覆盖正式状态。
- 删除历史 migration、Temporal replay fixture 或仍被持久数据引用的协议 schema 不属于仓库瘦身。
