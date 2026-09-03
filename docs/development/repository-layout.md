# 仓库布局与边界

当前 `main` 采用按职责分区的单仓库结构。目录名称表达源码所有权；Docker 服务名、
JWT issuer/audience、Temporal task queue 和持久化协议标识属于运行时合同，不随物理目录
重命名。

## 顶层目录

| 目录 | 所有权 | 允许内容 |
| --- | --- | --- |
| `apps/` | 可独立构建和部署的应用 | Java 领域服务、Python Agent Runtime、Vue Web、OCR Parser |
| `contracts/` | 跨进程机器合同 | JSON Schema、兼容矩阵、回放 fixture、生产合同目录 |
| `infra/` | 部署与平台配置 | 基础服务配置、Compose、环境资源、Kubernetes、可观测性 |
| `tools/` | 人工或 CI 调用的工具 | 开发启停、代码生成、验证、UAT、受控恢复工具 |
| `tests/` | 跨应用验证 | 静态契约、API 集成、E2E、性能、基础设施测试 |
| `docs/` | 面向人的当前文档 | 架构、接口、合同、部署、测试、发布与运行手册 |

仓库根目录只保留全仓入口和编排文件，例如 `README.md`、`docker-compose.yml`、
Git/GitHub 配置及 Agent 协作约束。

## 应用边界

- `apps/domain-service`：Java 领域账本、公共 API、正式 Finalizer、Temporal
  Workflow/Worker 与 Flyway 数据库迁移。
- `apps/agent-runtime`：Python 模型访问、LangGraph/LCEL、受治理的认知执行和 Graph
  持久化迁移。这里的 `migrations/` 只维护 Graph Runtime 自有表，不迁移 Temporal Server
  数据库。
- `apps/web`：六站争议旅程和人工审核界面，只通过公开 API/SSE 读取或提交授权动作。
- `apps/ocr-parser`：证据文件解析和回调适配，不拥有案件正式状态。

应用内部测试继续与源码共置；只有需要跨应用、基础设施或仓库结构的测试放入顶层
`tests/`。

## 基础设施边界

- `infra/services` 保存 PostgreSQL、Temporal、Nginx、MinIO、Elasticsearch 和 LiteLLM
  的配置资产；Temporal Server 并未删除，其动态配置位于
  `infra/services/temporal/dynamicconfig/`。
- `infra/compose` 保存隔离环境的 Compose 定义；根 `docker-compose.yml` 仍是本地/CI
  全服务入口。
- `infra/environments` 保存特定环境资源，不放业务源码。
- `infra/kubernetes/production` 与 `infra/observability` 分别保存生产部署和观测资产。

仓库结构调整不授权核心组件升级。Temporal/PostgreSQL 的版本或 schema 变更必须经过
独立备份、迁移、回滚和验收流程，不能由普通开发启动脚本隐式完成。

## 依赖方向

```text
apps/*  ──读取──> contracts/*
tools/* ──编排──> apps/* + infra/*
tests/* ──验证──> apps/* + contracts/* + infra/* + tools/*
docs/*  ──说明──> 当前代码与合同
infra/* ──部署──> apps/*（不反向拥有业务规则）
```

生产事实权威仍是 Java/PostgreSQL，Temporal 负责持久流程，Python/LangGraph 负责认知
建议，Web 负责交互。目录移动不得改变这些状态所有权。

## 新增内容落位规则

1. 可部署进程进入 `apps/<service>`，并拥有自己的构建、依赖和服务内测试。
2. 跨语言且需要兼容/回放的结构进入 `contracts/`，不得随实现重构重编号。
3. 数据库迁移跟随其数据所有者：Java Flyway 留在 `domain-service`，Graph migration
   留在 `agent-runtime`，平台数据库迁移走独立运维流程。
4. 可重复的开发或运维命令进入 `tools/`；一次性调查输出、日志、缓存和构建产物不提交。
5. 跨服务门禁进入顶层 `tests/`；用户文档统一进入 `docs/`。
