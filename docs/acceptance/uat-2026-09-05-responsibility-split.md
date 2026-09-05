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

## 统一入口与真实浏览器 E2E（2026-09-06 00:42—01:01）

### 被测快照及边界

- 分支：`codex/large-file-responsibility-split`。
- 精确候选：`1b5923c0e28bfe1ad51c83f1c4997e2a3e736985`，测试前工作树干净。
- 固定入口：`python tools/uat/production-runtime/start.py`。
- 隔离 run：`p9-56e5404bd5de`；浏览器入口 `http://127.0.0.1:25180/disputes`。
- namespace：`after-sale-flow-p9-p9-56e5404bd5de`；CONTROL build：`p9-control-1b5923c0`。
- 统一入口首次启动及同 run 再次执行均返回成功；基础设施 readiness 通过。
  这仍只是 `INFRASTRUCTURE_READY_ONLY`，不是业务 E2E 通过。
- 本轮独自使用 Codex 内置浏览器：表单创建、双方切换、陈述、确认、文件选择与上传
  均通过实际页面；只读接口/数据库用于核对结果。没有 API 代替 UI 写入，没有手工改数据库、
  Temporal history、流程状态或模型结果，没有升级或重启既有核心组件。

### 新案件与执行结果

案件 `CASE_P9_SYNTHETIC_1`，订单 `UAT-20260906-CUP-01`，用户 `user-local`，
商家 `merchant-local`。场景为虚构的 49 元蓝色陶瓷杯错发白色；本案仅申请核验与解释，
不发起真实退款、补发、赔偿或其他财务动作。

| 验收步骤 | 结果及证据 |
| --- | --- |
| 表单创建新案 | 通过，浏览器返回新案件接待室 |
| 用户补充、确认、商家独立接待与确认 | 流转通过；6 条 INTAKE run 全部 `COMPLETED/COMMITTED`，attempt 无 error |
| 接待内容准确性 | **失败**：用户明确否认放弃权利后，回复承诺纠正，但诉求卡片刷新后仍显示“放弃其他经济补偿诉求” |
| 接待室进入证据室 | 通过，双方封存后进入证据室 |
| 证据开场 Graph/正式提交 | 通过，run `target-evidence-run:b551dd3feb373555bf4e6db2d8050f49` 为 `COMPLETED/COMMITTED`，模型 attempt 成功 |
| 证据室状态展示 | **失败/阻塞**：状态接口持续 HTTP 400，页面误报“证据书记官生成失败” |
| 商家上传与文件解析 | 存储/解析通过：POST HTTP 201，后续 GET 返回解析后的完整文本；UI 未正确确认成功 |
| 双方证据批次、庭审、评审、结果 | **未执行**：不得绕过证据状态门继续 |

### 阻塞 E2E-01：真实模型 profile 被只读投影硬编码拒绝

实际浏览器请求：

```text
GET /api/disputes/CASE_P9_SYNTHETIC_1/evidence/process-projection?view=active
HTTP 400 / INVALID_ARGUMENT
details.reason = target modelProfileId must equal production-runtime.contract-blocked
```

`EvidenceProcessProjectionAdapter.targetPins()` 已先核对 activation 的精确 runtime pins，
但 `EvidenceProcessProjectionView.VersionPins.target()` 又强制 model profile 等于
`production-runtime.contract-blocked`；`hasTargetComposite()` 也重复同一限制。
固定启动配置的真实模型 profile 为
`qwen3.8-flash.uat.9c5016a2af54c00a57ce41e6cf6e35ec9e8d22f8deadd03ebc3c2eb5d4f70ae9.v1`。
因此真实模型正常运行后，读取证据室投影反而确定性失败。

- 违反不变量：已由 activation 验证的同一模型配置，应被正式执行和正式状态读取一致接受。
- 不是本次证据开场模型失败：其账本明确 `COMPLETED/COMMITTED`，
  `public_output_emitted=true`、`final_frame_observed=true`，无 error code。
- `git diff main -- EvidenceProcessProjectionView.java`（完整源码路径）为空，
  `git show main:<path>` 仍有相同硬编码。这是当前真实模型装配暴露的既有契约缺口，
  不能归因于本次职责拆分。
- 待修复边界：统一接收经 activation 精确绑定的合法模型 profile；保留缺失、非法、
  跨配置不一致的拒绝路径，不通过关闭校验或改为“禁用模型”掩盖。
- 所需最小回归：`EvidenceProcessProjectionAdapterTest` 增加真实 profile 正例、
  错误 activation/profile 负例和原 contract-blocked 邻接；修复后复跑同入口浏览器流程。

### 连带缺陷 E2E-02：上传成功与刷新失败合并为上传失败

浏览器只发出一次文件上传，201 响应的证据 ID 为
`EVIDENCE_7d0cfcc6e44f4943b6110d8cd30d4337`，原文件 `warehouse-dispatch.txt`，
848 bytes，SHA-256 `a306300b7232541ed949f8b4f80bfee3e0575108aa47a368713c5382a31d5757`。
后续 GET catalog 已有该条 `PENDING_SUBMISSION` 及解析文本；材料内明确声明是软件 UAT
模拟记录，不是真实业务凭证。

`EvidenceRoomView.vue` 的 `confirmEvidenceUpload()` 把 POST 和后续 `refreshWorkspace()`
包在同一 catch；后者的 `Promise.all` 被 E2E-01 的 400 拒绝，导致成功目录不发布、弹窗不清空，
再次显示“确认声明并上传”。这会误导用户重复上传。

- 违反不变量：已成功提交的上传回执不能因后续 GET 失败而被展示成未提交。
- 本轮未点击第二次上传，关闭弹窗后保留唯一证据记录；未删除、补写或伪造提交状态。
- 待修复边界：区分上传事务结果与刷新失败；保留成功证据 ID，刷新失败时只能重读，
  不能引导同文件重新 POST。投影未知时依然禁止越过业务授权门。
- 所需最小回归：201 + projection GET 400 的前端回归，断言上传只调用一次、成功回执不丢失，
  并覆盖正常上传及真实 POST 失败相邻行为。

### 内容缺陷 E2E-03：已明确纠正的诉求未同步到卡片

初始“不申请退款、赔偿或补发”被扩写为“放弃其他经济补偿诉求”。用户随后两轮明确反对，
最终回复承诺纠正，案件摘要已记载纠正，但持久化重新加载后诉求卡片仍是旧句；
商家完成陈述后其回应卡片也仍显示“尚未直接陈述”。这是可复现的内容一致性失败。
当前仅证明症状及非页面缓存，尚未证明责任在模型分支、合并器还是卡片选用字段；
不得归类为随机模型抖动或直接用前端字符串替换掩盖。

下一修复需对照每轮 proposal 与正式 dossier，验证“本次未申请”不会被升级为权利放弃，
且明确纠正能同步到权威字段及 UI，并保留对方不能篡改发起方诉求的负例。

### 本轮判定与现场保留

**结论：E2E 已实际执行，但未通过；完整六阶段验收被 E2E-01 阻塞，禁止据此推送/发布。**
不将基础设施健康、7 次 Graph 正式成功或单次上传成功等同于完整业务稳定。

内置浏览器保留当前案件证据室；测试数据库、7 条 run、唯一待提交证据未清理。
截图保存在本机私有运行目录：
`C:/Users/Jupiter/.after-sale-flow/production-runtime-local/p9-56e5404bd5de/evidence/e2e-evidence-blocked.png`。
本轮只新增该验收记录及私有测试材料/截图，没有修改生产源码，未提交或推送。

## 2026-09-06：阻塞修复候选（尚不构成 E2E 通过）

- E2E-01：新增显式 `evidence-process-projection.v2` 读取契约，TEMPORAL 投影允许
  exact activation 绑定的真实模型 profile；保留 v1 冻结文件及 blocked-profile 边界。
  见 ADR 0019。未改数据库、Workflow 历史或正式写入权限。
- E2E-02：上传 201 后立即保留证据回执、清理提交草稿；后续 GET 失败单独提示，
  锁定依赖投影的写入，只允许 GET 刷新，不再次 POST。切换角色后不泄露旧回执。
- E2E-03 已定位：初始生成摘要把“本次不申请”扩大成权利放弃，冻结矩阵也承接了该摘要；
  后续并行事实增量没有重写冻结诉求的权限，回复不得声称已经修改正式卡片。
  为初始摘要及并行 Frame 增加否定/范围保留与禁止虚假修改承诺的提示词约束。
  历史错误不作字符串替换或手工 DML；新模型行为仍须新案件 E2E 验证。
- 商家直接回应已存在于 `case_fact_matrix.v2.claims.respondent_direct`，但旧卡片只读
  `respondent_attitude`。改为优先读取有 exact role、直接来源及 source refs 的正式矩阵回应，
  保留发起方不可查看对方私有回应及旧卷宗兼容边界。

最小回归实跑：

- `EvidenceProcessProjectionAdapterTest`：21/21 PASS（含 schema/hash/replay/activation 负例）。
  一条旧 SQL 文本断言从 epoch 别名同步为实际的 candidate 子查询别名，查询本身未改。
- `tests/static/test_evidence_projection_v2.py`：10/10 PASS。
- `EvidenceRoomView.test.js` 定向 selector：7/7 PASS，其余 75 项未运行。
- `IntakeRoomView.test.js` 定向 selector：7/7 PASS，其余 88 项未运行。
- 提示词组合定向 pytest：4/4 PASS；这仅证明提示词装配，不证明模型生成稳定。
- Vite production build PASS（保留既有大 chunk 警告）；`git diff --check` PASS。

用户已明确授权：备份后按原版本重建这一套隔离 UAT，旧主环境不动。
备份目标为旧 run 私有 evidence 目录下 `backup-before-rebuild`，包含 Domain/Graph/Temporal
两库导出、MinIO 原始对象以及校验回执；重建通过正式 `start.py`，不升级核心组件。
候选可本地提交以绑定镜像源码；在新浏览器全流程通过之前不得推送/声明生产可发布。

### 候选 c1ae8cce 的浏览器复测与第二个修复候选

原 run `p9-56e5404bd5de` 已完成四份数据库导出、Domain 实际恢复校验（1 案件/1 证据/7 run），
MinIO 以完整 tar 保留 140 项（对象名含冒号，不能解包到 Windows 普通目录）。精确 teardown PASS，
原主环境未动。固定启动入口构建 `c1ae8cce50ea4d2bc16442e74373ac74e4261fc3`，新 run
`p9-39ae5fea0cab`，全部基础服务 healthy；仍不据此声明 production lane 或 E2E 通过。

浏览器从表单创建新 `CASE_P9_SYNTHETIC_1`。首轮 COMPLETED，摘要为“暂不涉及退款或赔付诉求”，
没有扩大成放弃权利。用户补充轮 `target-intake-run:7d84fea309c136dbbdd91bcceccc874a` ABORTED：
Python 日志两次指向 `intake_turn_dossier_frame.public_projection_items[2].source_row.fact_key`，
同时违反既有 FACT literal 和本轮 NEW 前缀 pattern。未重发该消息，没有提交兜底卷宗。
截图：该 run 的 `evidence/e2e-dossier-fact-key-failed.png`。

第二修复只收窄 Provider 新生成契约：服务器把本轮前缀派生为最多 5 个完整 NEW key，与已有 FACT
一起放入一个有限 enum；模型不再自行拼接长哈希。稳定 Frame、Java 命名空间校验和已保存
checkpoint 的读取/重放保持不变，没有修正或接受越界标识。9 项定向回归通过，覆盖 enum、
旧事实/双方角色、错误标识零发布、三图并行及 checkpoint 重放零重复 provider 调用。

同时修复镜像构建日志依赖 Windows 默认 GBK 的问题：显式 UTF-8 解码，不改变退出码检查、
镜像版本或构建权威。第二候选仍须重新部署和完整浏览器验收，未推送。

### 候选 24cd2921：新增事实门通过，备注邀请占位暴露

run `p9-a753d0be623c`（同一套锁定基础镜像）浏览器重新从表单建案。首轮及含新增事实的
用户补充轮均 COMPLETED，分别为 `target-intake-run:8321f891b29439fea4a47ab2737db4ed`、
`target-intake-run:c179e96d962f38bc9acf46dca47a7d9e`，未重发消息，事实标识修复获得运行验证。
下一“陈述完整”轮却在 READY_PENDING_REMARK_INVITE 阶段 ABORTED：两次 Dialogue 生成均因
`dialogue.remark_disposition` 的 `none_required` 校验拒绝（response_chars=166）。
这是独立的模型生成服务端固定占位问题，不是先前 fact-key 失败复发。

第三修复：该阶段使用显式 Provider draft `IntakeDialogueTransitionGenerationV6`，只生成公开回复，
固定 `remark_disposition=null` 由 materializer 补齐。WAITING_FOR_REMARK 仍要求模型给出
REMARK/NO_REMARK，NOT_READY 不变；旧 V5 类型和持久化 Frame v3 读取不删除。
5 项最小回归通过：实际邀请阶段三图 stream、checkpoint 重放零新增 provider 调用，
Provider 禁止提前写备注字段、WAITING 邻接及提示词装配。未放宽正式阶段或 schema 校验。
仍未完成证据到结果页，未推送；截图留在该 run `evidence/e2e-dialogue-placeholder-failed.png`。

### 2026-09-06：按持久阶段裁剪 Dialogue 的模型任务

按用户要求保留既有 Java 状态机，只调整 Dialogue Provider 输入、提示词和生成 Schema。
根因不只是固定 null 曾交给模型填写：同一个系统模板同时描述多个阶段，WAITING 的输入还携带
预设 `ACK_REMARK` 动作和旧追问槽，暴露了当前任务不应使用的指令与占位结论。
违反的不变量是“模型只能执行当前持久阶段授权的任务，不得选择阶段或代写程序确定的值”。

- NOT_READY：当前消息、近期对话和已有问题背景；仅允许 ACKNOWLEDGEMENT。
- READY_PENDING_REMARK_INVITE：当前消息和明确标为 previous_question_slots 的上一轮问题；
  不提供近期对话/新追问任务，仅允许 TRANSITION，不生成 dialogue 字段。
- WAITING_FOR_REMARK：当前消息和近期陈述；删除问题槽及预设动作，只在此阶段允许
  REMARK_ACKNOWLEDGEMENT 与 REMARK/NO_REMARK 分类。模型并未获得修改正式诉求的权限。

三个专用阶段模板由从已校验持久阶段选择的 V7 输出类型携带非 JSON 的 ClassVar 判别值，
Harness 使用该值选取唯一模板；不从案件文本、模型回答或路径猜测阶段。缺失/未知阶段及
向其他节点使用 Dialogue 阶段均拒绝。三个模板全部计入 instruction-pack 内容哈希，
启动资源检查会验证它们存在；实际发给模型的仅为当前阶段模板。系统通用 JSON 安全规则保留。

完整 sealed model context、状态枚举、旧 V4/V5/V6 草稿读取类型和稳定 Frame v3 保留，
只有 Dialogue 的 Provider 视图使用显式 frame-provider-input.v2；Dossier/Quality 视图不变。
非备注阶段仍由 materializer 补 null，不允许模型提前填写，备注阶段不得遗漏分类。

36 项定向 pytest PASS（88 项未选择）：包括三阶段 Schema 正负例、专用提示词隔离、
模板缺失/内容哈希、缺失阶段零 Provider 调用、真实 Harness + 假 Provider 的四种阶段/分类组合、
三图 stream 和 checkpoint 重放零重复调用、原始上下文不变及相邻 Harness/角色装配。
这些是本地确定性回归，不是真实模型或浏览器 E2E 通过；不保证模型此后所有语义输出都正确。
本轮未重启或升级服务、未写运行数据库、未推送。部署及证据到结果页验收仍待完成。

另补跑 3/3 PASS：`test_legacy_v1_reset_complete_checkpoint_replays_without_provider`、
`test_complete_checkpoint_replays_only_missing_prefix_without_provider`、
`test_dialogue_single_visible_item_seals_without_terminal_slot_echo`，合计 39 项。
本轮最终命令（工作目录 `apps/agent-runtime`，没有启动 Docker/Postgres 测试容器）：

```powershell
python -m pytest tests/harness/test_prompt_composer.py tests/harness/test_model_runner.py tests/graphs/intake/test_parallel_outputs.py tests/graphs/intake/test_parallel_graph.py tests/graphs/intake/test_parallel_contracts.py -q -k 'dialogue_phase or parallel_dialogue or provider_visible_schema or ready_invitation_stream or ready_pending_respondent or provider_payloads or three_physical_graphs_stream or production_bundle_deterministically or parallel_intake_frame_prompt or preserve_claim_scope or rejects_rehashed_lane or rejects_lane_hash_drift_on_replay or action_binding_must_match or model_runner_composes_prompt_with_managed_context_window or test_async_stream_preserves_semantic_validator_and_generation_reset'
python -m pytest tests/graphs/intake/test_parallel_graph.py -q -k 'legacy_v1_reset_complete_checkpoint_replays_without_provider or complete_checkpoint_replays_only_missing_prefix_without_provider or dialogue_single_visible_item_seals_without_terminal_slot_echo'
```
