# Production Runtime 生产重构（2026-09-04）

## 目标

将曾用于验证目标架构的 `target-e2e` 实现正式收敛为生产运行时，并让生产代码、UAT
工具和平台配置在目录与命名上清楚分离。本次是首次生产发布前的 clean break，不承接旧
UAT 的数据库、Graph checkpoint 或 Temporal History。

## 备份与工作分支

- 重构前 `main`：`265667ac9224d69a040bfa3324343f2b27cd4f67`
- 本地与远端备份：`codex/main-pre-production-runtime-refactor-20260904`
- 重构工作分支：`codex/production-runtime-refactor`

备份分支只用于审计和按文件恢复，不作为后续开发或生产发布分支。

## 生产结构

| 旧边界 | 当前生产边界 |
| --- | --- |
| Java package `workflow/targete2e` | `workflow/runtime` |
| Java source set `src/target-e2e` | `src/production-runtime` |
| Maven profile/classifier `target-e2e` | `production-runtime` |
| Python `target_e2e*.py` | `production_runtime*.py` |
| Graph lane `TARGET_E2E_CANDIDATE` | `PRODUCTION` |
| 配置前缀 `target.e2e.*` | `production.runtime.*` |
| 合同目录 `contracts/agent-platform/target-e2e` | `contracts/agent-platform/production-runtime` |
| 隔离部署工具 | `tools/uat/production-runtime`、`infra/compose/production-runtime-uat.yml` |
| 隔离数据库默认身份 | `production_domain`、`production_graph` |

`apps/domain-service/src/main` 保留可复用领域与运行时核心；
`apps/domain-service/src/production-runtime` 只负责正式生产 Bean、Worker、激活和隔离装配。
生产镜像必须运行 Maven `production-runtime` profile 生成的 classifier JAR，不能只运行默认
JAR。

## 合同与数据边界

- 当前组合基线为 `production-contract-baseline.v1`。
- `agent-stream.v4`、`agent-stream.v3`、`hearing_flow.v2` 等仍是各自协议的真实版本，不能
  为了视觉统一而重编号。
- 旧 `target-e2e` 标识、activation、checkpoint 和 History 不进入生产兼容范围。
- 首次生产部署必须新建 Domain PostgreSQL、Graph PostgreSQL 和 Temporal namespace。
- 从当前生产基线开始，已经发布的 wire/schema 版本不可原地改写；后续通过新增版本演进。

## 平台边界

本次提交只调整应用源码、合同、测试、文档和隔离 UAT 资产，不升级或重启 Temporal、
PostgreSQL、Docker 或其他核心组件。仓库默认 Compose 版本也不会因本次重构自动改变。
核心组件升级必须另行备份、迁移、回滚演练并取得明确授权。

## 发布门禁

1. 运行代码、构建路径和部署配置不得再出现旧 `target-e2e` package、配置键、profile、
   classifier 或 Graph lane；发布说明可以保留迁移前名称用于审计。
2. Java 主源码与测试编译通过，聚焦 Production Runtime 测试通过。
3. Python production runtime/settings/bindings 聚焦测试通过。
4. Web 类型检查/测试与生产构建通过。
5. Maven `production-runtime` profile 生成可启动 classifier JAR，marker 与源码提交绑定。
6. 隔离 UAT 静态门禁和 Compose 引用一致；不接触现有核心服务。
7. 正式发布到新环境后，再运行新 namespace 的 synthetic routing 和 fresh-case 浏览器 E2E。

## 本地验证结果

- 仓库结构、导入边界及 Production Runtime UAT 静态门：`81 passed, 14 skipped`。
- Evidence/Temporal 合同静态门：`46 passed`。
- Python production settings/runtime/bindings：`123 passed`。
- Java 主源码与测试源码编译：`BUILD SUCCESS`。
- Java Production Runtime 契约/激活/装配聚焦测试：`99 passed`。
- Web 流式状态与 Evidence 页面：`96 passed`；Vite 生产构建成功。
- Maven `production-runtime` 独立源码集：28 个类编译成功。

这些结果验证当前源码和制品装配，不冒充新生产环境的运行验收；新数据库、新 Temporal
namespace 和正式镜像部署后的 synthetic/fresh-case E2E 仍是发布时门禁。
