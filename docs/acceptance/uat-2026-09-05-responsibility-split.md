# 职责拆分版本：浏览器 UAT 记录（2026-09-05）

## 结论

`BLOCKED_BEFORE_CASE_CREATION`。本轮使用 Codex 内置浏览器操作真实前端，未使用
Mock API、旧版应用镜像或历史案件替代当前版本。完整六阶段 E2E 未通过，不满足推送门禁。

- 分支：`codex/large-file-responsibility-split`
- 被测源码：`18801bd99a98ae9cb8dded4af9410d0cf099752b`
- 前端：当前工作区 Vite，`http://127.0.0.1:5173/disputes`
- 时间：2026-09-05 23:02（Asia/Shanghai）

## 实际浏览器操作

| 检查 | 实测结果 |
| --- | --- |
| 页面呈现、体验角色选择器 | 可打开和操作 |
| 点击“发起争议审理” | 新案件表单正常打开 |
| 缺少陈述时提交 | 浏览器阻止提交，并提示“请填写此字段” |
| 填写虚构保温杯规格争议并提交一次 | 页面显示“服务返回了不可解析的响应（HTTP 500）”，未进入接待室 |
| 接待、证据、庭审、审核、结果 | 未执行：创建案件前置条件失败 |

唯一提交的测试订单引用为 `UAT-20260905-STRUCTURE-01`，没有真实订单、付款或个人资料。
未重发该请求。没有取得 case ID；代理日志证明该请求无法连接 Java，而非模型或 Graph 执行失败。

## 阻塞证据和责任层

1. **应用运行环境未就绪。** `5173` 有当前前端进程监听，`8080` 和 `18000` 没有监听。
   前端代理在 23:02:55 对 `/api/disputes` 报
   `connect ECONNREFUSED 127.0.0.1:8080`。这是 Java 应用未运行，不是模型生成抖动。
2. **当前隔离 UAT 装配仍保留模型阻断占位配置。**
   `infra/compose/production-runtime-uat.yml` 的 Python 配置使用
   `http://model-contract-blocker:4000` 和 `production-runtime.contract-blocked`；
   `tools/uat/production-runtime/provision.py` 生成的绑定也使用这个模型 profile。
   `application-contract-gates.json` 明确要求独立的真实模型运行时证明。
   不能只将占位值替换后宣称完整装配和隔离门已通过。
3. **现存历史启动脚本不适用于当前构件。** 候选工作区 `.local-dev/launch-source.ps1`
   仍绑定 `target-e2e` 路径、profile 和 activation。当前代码已 clean break 到
   `production-runtime`，因此未执行这些历史脚本，未接入旧库或旧 Temporal History。

## 额外 UI 问题

列表请求失败时，页面仍显示“还没有争议订单”，未区分“读取失败”和“成功读取空列表”。
`stores/resource.js` 已保存 `status=error` 和异常，但 `DisputeOverviewView.vue` 的空态
仅按案件数量选择。此轮只记录发现，未修改业务代码；修复应分别覆盖加载、空列表、请求失败
及保留旧数据时的失败状态，不应把失败转换成成功空列表。

## 继续条件

- 为当前生产构件提供可审计、可重复的启动配置，以及全新 Domain/Graph 数据库和
  Temporal namespace；不承接旧 UAT 的持久化状态。
- 接通真实 `qwen3.8-flash` 调用及其准确的 Graph/model binding，保持权限和网络隔离校验。
- 确认 API、Python、CONTROL、AGENT 就绪，再从浏览器创建新案件并完成所有阶段。
- 若涉及现有核心组件升级、重启或权限/网络范围扩展，先取得明确授权。
- 完整 E2E 通过后才提交发布结论并推送。已有聚焦测试通过不替代此门禁。

本轮未升级核心组件、未迁移或删除旧数据、未启动旧版业务 worker、未推送 Git。

## 职责拆分稳定性回归补充（23:09—23:15）

按本次优化的实际范围复测，并对失败项使用本地 `main`
（`71fda5a0d5e39560c98c07be38ae9bd27c4b716b`）的只读归档副本进行对照。
没有切换或覆盖当前工作树，也没有修改断言来消除失败。

| 检查范围 | 当前分支 | main 对照 | 结论 |
| --- | --- | --- | --- |
| Java StagePlan / ClarifiedMatrixValidator / HearingFlowRuntimeService | 15 通过，0 失败 | 本轮未重复 | 拆分相关测试通过 |
| Python guidance / prompt compaction / parallel graph | 36 通过，0 失败 | 本轮未重复 | 直接相关测试通过 |
| Python 旧 case-detail dossier 测试文件 | 30 通过，45 失败 | 30 通过，45 失败 | 失败用例名称集合完全一致 |
| Vue HearingCourtView / ReviewWorkbenchView | 89 通过，7 失败 | 89 通过，7 失败 | 失败用例名称集合完全一致 |
| Web 生产构建 | 成功 | 未重复 | 两个拆出的 CSS 均生成生产资源；仍有大 chunk 警告 |

Java 命令：

```text
./mvnw.cmd -Dtest=HearingFlowStagePlanTest,HearingClarifiedMatrixValidatorTest,HearingFlowRuntimeServiceTest test
```

Python 命令（在 `apps/agent-runtime`，使用已有 `D:/miniconda/python.exe`）：

```text
python -m pytest tests/agents/test_intake_guidance.py tests/agents/test_intake_prompt_compaction.py tests/graphs/intake/test_parallel_graph.py -q --tb=short
python -m pytest tests/agents/test_intake_case_detail_dossier.py -q --tb=no --junitxml=<报告路径>
```

Web 命令（在 `apps/web`）：

```text
node node_modules/vitest/vitest.mjs run src/views/disputes/HearingCourtView.test.js src/views/reviews/ReviewWorkbenchView.test.js --reporter=json --outputFile=<报告路径>
node node_modules/vite/bin/vite.js build
```

进一步核对：

- 两份 Vue 文件的 `<script>` 和 `<template>` 与 `main` 完全相同；本次只提取样式。
- 前端失败涉及旧的三个决策按钮数量、双方回答阶段已不展示的提交状态栏、以及评审指标
  的 fixture/展示预期。它们在 `main` 原样复现，不能归因于 CSS 提取。
- Python 旧测试中存在未传 `conversation_action` 以及旧模型输出 schema 的调用；
  `main` 的 `render` 已同样要求该参数。所有 45 个失败均在同一环境下复现。
- 首次选用应用 `.venv` 时缺少 pytest，未运行测试；没有因此安装或升级运行依赖，
  后续改用已有测试环境。
- 此前通过的 `ProductionHearingArtifactConfigurationIT` 为 1/1，生产构件 marker 仍精确
  绑定当前 `18801bd9…`。本段不将其计作一次新运行。

本次差分回归**未发现新增失败**；这不等同于现有测试全绿，更不等同于完整业务 UAT 已通过。
真实浏览器六阶段验收仍未完成。上述差分回归时没有修改业务代码。

## 启动配置补齐（进行中）

按用户“补齐启动配置然后 UAT 当前代码”的要求，继续使用现有版本的镜像，
在官方隔离 UAT 装配中新增显式 `--model-env-file` 输入。只读取现有
`LITELLM_DEFAULT_MODEL`、`DEFAULT_LLM_API_BASE`、`DASHSCOPE_API_KEY`；不输出或提交密钥。
不指定该输入时仍保留模型阻断模式。

- 新增私有 Nginx 模型代理，仅允许 `POST /v1/chat/completions`，固定 HTTPS 上游、
  校验证书、不自动重试；Python 仍只连接私有网络，不接触 Domain 数据库。
- 模型配置摘要绑定至 Graph model profile，预检拒绝配置/签名绑定漂移。
- 补齐 Java 必需的执行提供方标识，保持现有 `LiteLLMClient.governed_provider=litellm`。
  这是客户端协议标识，不声称启动了旧 LiteLLM 服务。
- 聚焦检查：`test_production_runtime_uat_model_config.py` 与
  `test_phase9_production_runtime_deployment.py` 合计 40 通过、5 跳过；
  跳过项是工作区不存在的可选历史 operator 脚本，不是新模型配置测试。

当前计划为：构建精确提交 → 新建隔离数据与 namespace → 启动并验证 → 内置浏览器新建案件。
此节不是 E2E 通过声明，不允许据此推送发布。

## 隔离装配实测（2026-09-05 深夜—09-06）

- `135c0f6d697dd18710b6277659c9224d347bbb94` 的四个应用镜像已由正式构建工具生成并锁定；
  Java 生产 profile 编译、打包成功。构建使用跳过测试参数，不计作测试套件通过。
- Maven 发布依赖曾被传递仓库的未认证端点拖慢；将既有官方 Central mirror 的范围改为
  `external:*`，不改变依赖版本。仓库配置聚焦测试 1/1 通过。
- 新隔离 run `p9-20260905-split01` 使用新数据库、新 namespace 和现有 Temporal 1.29.7
  镜像。宿主默认 Docker 地址池已耗尽，只为该 run 的缺失网络分配经过路由/重叠检查的
  独立地址段；没有删除历史网络或更改 Docker 全局配置。
- Domain/Graph 数据库、Graph migration/restore 验证、Temporal/namespace、模型代理、
  Redis、MinIO、Elasticsearch 和前端已启动。Python 在业务请求前发生
  `IndexError: 4`：`graph_lifecycle._contract_codec()` 把源码仓库的固定目录深度用到
  `/app/app/api/graph_lifecycle.py` 容器路径。Java 服务因此未获准启动，未创建案件。
- 最小修复：容器通过 `AGENT_CONTRACT_ROOT` 指向已有只读协议挂载；源码运行保留
  仓库定位方式。显式无效路径、缺失 inventory 均拒绝，不回退到猜测目录或放松 schema。
  `tests/api/test_graph_contract_resources.py` 实际执行 5/5 通过，包括完整 codec 加载和缓存复用。

该发现属于容器资源定位缺陷，不是模型生成问题；完整业务 E2E 仍待修复镜像启动后验证。

## 第二轮启动门（2026-09-06 00:16—00:24）

`379c598b71fa6cd0b420817305d97586633dcabc` 的正式镜像构建成功。新 run
`p9-20260906-split02` 中 Python 已通过健康检查，UDS 和 mTLS 代理正常；API 也曾启动成功。
但 CONTROL 在 Spring 装配阶段失败：`ProductionActivationRuntimeConfiguration` 与
`ProductionControlConfiguration` 同时发布 `JdbcProductionActivationStores`，使 verifier
按 `ProductionActivationReplayStore` 注入时得到两个候选。AGENT 因 activation 尚未注册而
连带启动失败，故没有提交业务请求。

- 修复：删除 runtime verifier 配置中的重复 factory，保留 CONTROL 的原共享 store。
  不修改存储实现、签名校验或 workflow 行为；API 的独立角色装配保持不变。
- 回归：`ProductionActivationStoreAssemblyIT` 加载真实生产配置。旧代码准确复现两个 bean；
  修复后 1/1 通过，覆盖 CONTROL/API 三个存储接口引用同一实例，以及 AGENT 不注册该 store。
  测试不启动外部 worker、不访问数据库，因此不替代 live 启动验收。
- 执行：`mvnw.cmd -Pproduction-runtime -Dproduction-runtime.skip-unit-tests=true
  -Dproduction-runtime.source-sha=379c598b71fa6cd0b420817305d97586633dcabc
  -Dit.test=ProductionActivationStoreAssemblyIT verify`，BUILD SUCCESS。
- 调整 Dockerfile 中源码 SHA 参数的位置：依赖缓存完成后才引入，打包仍使用同一精确 SHA。
  `test_production_java_build_binds_source_only_after_dependency_cache` 1/1 通过；无版本升级。
- 第二轮只读数据库检查：`fulfillment_dispute_case=0`、`case_command=0`。
  两次失败启动的日志/镜像/网络证据已导出，再按各自 host lock 清理临时资源；未删除历史数据。

截至此记录，完整浏览器业务流程仍未通过，尚未推送。

## 固定启动流程归一（2026-09-06）

用户要求停止手工维护临时启动步骤。本轮加入版本化 `start.py`、本机非秘密默认配置和
现有核心镜像 digest 清单；统一构建、私有配置生成、网络分配、预检、启动与 readiness。
网络分配被放到数据库 bootstrap 之前，保留零旧资源检查和精确 host-lock 清理边界；
重试只接受同一归属/拓扑，不 prune 历史 Docker 网络。运行目录自动生成，不提交密钥。

- `test_production_runtime_start.py` + 原数据库 bootstrap 回归：12/12 PASS。
- Java 激活装配相邻测试 `ProductionActivationRuntimeConfigurationTest`：7/7 PASS。
- `b5f4f3f5b46025df66ed3acd5a8b7706f7d231a4` 镜像构建已成功；统一入口修改需新的精确
  提交镜像，不能把这次构建当作新入口或业务 E2E 的通过证明。

尚待以统一入口实际启动并完成浏览器业务验收；未推送。
