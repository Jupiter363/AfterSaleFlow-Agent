# 职责拆分版本：浏览器 UAT 记录（2026-09-05）

## 最新状态（2026-09-06）

`12a31175` / `p9-72cad5499c8a` 已完成真实模型、内置浏览器全流程功能 UAT，且后台收敛通过：
新表单 → 双方接待与无备注确认 → 双方上传举证 → 四焦点庭审与补证 → V1/评审/V2 →
只读终审解释 → 人工无外部效果审批 → 双方结果。21 次 agent run 全部完成，21 条正式命令
全部 APPLIED，案件 CLOSED；人工 command completion 与正式结果 hash 相等，辅助 receipt
保持独立且 COMMITTED。满足本次功能验收后推送门禁，不等于全部测试套件或生产认证通过。
以下保留各轮原始失败和修复记录，不能将早期结论理解为最新进度。

## 首轮结论（历史）

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

### 2026-09-06：真实模型接待通过，审核解释客户端缺少 mTLS

固定启动新建隔离 run `p9-42501af575f1`，构件源码 `0d5e221636275bef62af2f223ae2b2bcf22f2cba`。
内置浏览器 `25180` 从表单创建虚构案件 `CASE_P9_SYNTHETIC_1`（蓝色杯收到白色、仅核验解释）。
使用真实 `qwen3.8-flash`，未启用 thinking。未升级组件，旧主环境未变。

- 用户邀请补充轮 `target-intake-run:1948f73b4a4032bf821656a4cfbd815a` COMPLETED；
  无补充轮 `target-intake-run:e2138bc865223fadb749e9c1b8587a40` COMPLETED。
  商家相同阶段也完成，双方最终确认进入证据室。没有重发接待消息。
- 双方各上传一份明确标注测试用途的文字说明，唯一证据 ID 为
  `EVIDENCE_5f45ebcab7ab48fba4744d51aead4be4`（商家）和
  `EVIDENCE_77a7ff8f82e14357a5b022ecba61f3e8`（用户）；parse=SUCCEEDED、submission=SUBMITTED。
  用户文件被标为需人工复核，没有自动处罚。上传回执正确保留。
- 双方分别填写 5 个庭审焦点、一次提交；补证均明确无其他材料。卷宗冻结，
  V1 → 评审 → V2 完成并生成冻结审核包，进入终审工作台。
- 模型 V2 仍提出未经授权的“7 日后自动触发退款资格评估”，仅是待审建议、未执行。
  这一独立语义质量风险需要人工排除，不应将本轮接待修复描述成所有模型输出都无误。
- 审核解释官首次提问运行 `AGENT_RUN_0993de946ce24b0bad496aacb956754c` FAILED，
  error=`AGENT_STREAM_TRANSPORT_FAILED`。Java 日志内层为 SSLHandshakeException / PKIX path building failed；
  Python 没有收到模型调用。现场累计 19 COMPLETED、1 FAILED，案件 WAITING_HUMAN_REVIEW。

根因：`AgentNdjsonStreamClient` 自行创建系统默认 HttpClient；隔离运行的 Python URL 为
`https://graph-mtls-proxy:8443`，但 API 未配置客户端证书库/受信 CA。不能用关闭 TLS 验证修复。
修复增加显式 SYSTEM/MUTUAL_TLS 客户端配置，复用既有严格 PKCS12 校验/TLS1.3/主机名校验，
通过命名 Bean 注入流客户端；API 只挂载 client.p12 与 trust.p12，保持其他角色、网络拓扑和 Graph
正式写入路径不变。缺材料/未知模式/明文 mTLS/忽略材料均拒绝，握手失败不降级或重复发送。

截图、输入材料、固定启动及备份回执保存在本机该 run 的 evidence 目录。首轮失败未被覆盖。

定向 Java 回归 20/20 PASS：4 个流客户端装配/拒绝/握手不降级测试、3 个共享 TLS 工厂回归、
13 个既有流协议/帧校验测试。配置静态回归 29 PASS、5 个历史可选本地脚本检查 SKIP；
新增 API 只挂载两份只读客户端材料的静态门 1/1 PASS。首次测试编译发现测试调用了包内
密码复制方法，改为测试反射后最终命令通过，未放宽生产方法可见性。

```powershell
./mvnw.cmd "-Dtest=AgentStreamTransportConfigurationTest,TrustedGraphTransportFactoryTest#advisoryClientRetainsStrictTlsAndRejectsInvalidMaterial+trustedFactoryBuildsBothTransportsFromOneTls13Proof+wrongPasswordAndMalformedKeyStoreFailClosed,AgentNdjsonStreamClientV2Test" test
python -m pytest tests/static/test_phase9_production_runtime_deployment.py -q -k api_advisory_stream
```

该 run 按既有授权静默备份：4 个 PostgreSQL dump + MinIO tar；Domain 真恢复计数
`1|2|20`，归档 286 entries，5 个文件 SHA-256 再核验一致。旧主环境未停止/删除。

### 2026-09-06：终审解释通过，发现辅助记录时间绑定缺陷

被测源码 `6625c0e14489a21c8c7afcc82f0aa619d914e4c7`，隔离 run `p9-ea4d2e19936c`，
由固定 `start.py` 创建，仍使用原有镜像 digest 和 qwen3.8-flash（thinking=false）。
浏览器重新表单创建 `CASE_P9_SYNTHETIC_1`；双方接待（包括邀请和无备注）、两份文本上传/解析、
两项庭审焦点、双方无补证、卷宗冻结、V1/评审/V2、用户只读草案和审核员终审入口均通过。
商家提交前看不到用户本轮回答。两份材料明确标注虚构 UAT，不冒充原始交易凭证。

终审解释官 `AGENT_RUN_3780779f8c9c4d3581a86c6507fb2434` 为 COMPLETED，真实回复成功，
没有 TLS 降级。模型指出 V2 回应声称已修订，但正文未落实；这一建议内容质量问题被保留，
人工理由明确排除限期惩罚、退款、权利放弃及把测试文本当作原始凭证。
只批准冻结计划中唯一 `TARGET_NO_EXTERNAL_EFFECT`；notifications/preconditions 均为空。
人工提交一次后，ReviewTask=APPROVED，case=CLOSED/OUTCOME，页面发布事件
`ACT_a82a33f2a69d7baced3db1b1c0cda143`。这不等于后台全部完成：

- `target-review-run:fde4ccd964b0390d8608dc7bd849623c` 为 FAILED / FINALIZATION_REJECTED。
- `JdbcTargetReviewAdvisoryProjectionPort.insert` 将 Instant 直接交给 `setObject`，pgjdbc
  无法推断类型，导致 advisory-only 时间线记录未提交；不是模型 Schema 或生成抖动。
- 修复仅将 event_time/created_at 转为 UTC OffsetDateTime 后绑定 timestamptz，不改正式决定、
  时间线序列、事件 hash、事务所有权或重放规则。
- 新增真实 PostgreSQL 16（同一既有 digest）测试；旧实现首先真实复现同一 PSQLException，
  不使用无法发现该驱动问题的 PreparedStatement mock。覆盖 UTC 时刻（非 UTC 数据库会话）、
  精确重放不新增事件或更新时间、冲突拒绝、调用方 rollback 和 autocommit 拒绝。

修复前 run 已静默备份并实际恢复验证 `1|2|21`（案件/证据/运行），MinIO tar 374 entries，
4 个数据库 dump + tar 保留于该 run 的 `evidence/backup-before-rebuild`。未重试人工决定，
未覆盖旧失败记录；旧主环境没有改动。必须在新隔离 run 重新完成端到端及后台收敛后才推送。

修复后 `./mvnw.cmd "-Dtest=JdbcTargetReviewAdvisoryProjectionPortTest,TargetReviewContractsTest" test`
通过 8/8（3 个真实数据库测试 + 5 个邻接合约测试），0 failure/error/skip；旧代码的单个决定性
回归先因与 live 完全相同的 Instant 类型异常失败。`git diff --check` 通过。

### 2026-09-06：备注阶段 Dossier 越权与 v4 失败收口

源码 `3262547d214e041b17767e64aa2ba93641b5074c`、隔离 run `p9-ea623ca259c6` 的新案件，
用户在邀请后回复“没有补充备注，按已确认的核验解释范围继续，不作其他权利放弃”。
运行 `target-intake-run:1d31839cc37a30ac9f56696e6eaa2a19` 停在 RESULT_READY/UNCOMMITTED。
有界 JFR 异常采样确认两条原因，不依赖推测，也没有重发消息：

1. `IntakeFinalizationRejectedException: post-threshold Intake messages cannot change frozen substantive authority`。
   Dialogue 已按状态裁剪，但 Dossier 仍把无备注确认抽取为新事实并生成 matrix delta，
   违反既有 post-threshold substantive freeze；不是 JSON 格式错误。
2. 失败记录器无条件调用 v3 entity transition，遇到 v4 抛出
   `operation requires an exact agent-stream.v3 Temporal AgentRun row`，掩盖原始拒绝并反复重试。

修复保持状态机及正式冻结规则不变：

- 新的 request-bound Dossier draft 在 READY_PENDING_REMARK_INVITE/WAITING_FOR_REMARK 下，
  仅有 maxItems=0 的 public_projection_items；没有事实字段或 respondent null 占位字段。
  模型上下文只保留可信阶段，不发事实矩阵、写 key 或消息；独立冻结提示词替换实质事实提示词，
  并纳入启动资源检查和 instruction hash。Dialogue/Java 仍保存真实备注。
- 物化器补齐既有 sealed Frame 空增量，Java assembler 对 post-threshold 非空 dossier/matrix
  明确拒绝。NOT_READY 的事实生成、历史 sealed Frame 读取和 checkpoint replay 保持。
- 按持久 protocol=v4 使用独立 entity transition 与 v4 writer。验证原 FINAL 的协议、hash、
  run/attempt/sequence/result/audience，保持原 Graph result 审计，在同一事务记录拒绝并写相邻
  sanitized ERROR；精确 replay 不新增事件，不公开未提交 FINAL，不进入 v3 writer。

本轮已按授权冻结备份并实际恢复验证 `1|0|5`（案件/证据/运行），MinIO tar 206 entries。
JFR、浏览器截图、4 个数据库 dump 和 tar 保存在该 run evidence 中。官方 teardown 仅移除
该 run 的 23 containers / 14 networks / 8 volumes；旧主环境仍健康运行。当前正在重新验证，
尚未把此轮或后续新环境声明为 E2E PASS，尚未推送。

Python 定向回归 81 PASS / 15 deselected，覆盖真实 Harness 的状态 schema/prompt/context 与
零额外模型调用的 checkpoint replay。额外尝试整个 prompt_composer 测试文件时，三条既有
Evidence/旧 Intake 提示词文本断言不匹配（3 failed / 87 passed 后停止），并非本轮定向门通过；
不将此结果描述成全套测试绿。

真实数据库门 `AgentRunV4FinalizationRejectionIntegrationTest` 1/1 PASS：使用同一 PostgreSQL16
digest 和全部正式 Flyway migrations，真实 JPA/ledger/v4 writer/stream delivery；仅不调用的
v3 writer 是 mock。验证 caller rollback 恢复 RESULT_READY 且没有 ERROR，正式提交产生唯一
相邻 v4 ERROR，run/attempt/高水位同步，重复调用不新增事件，冲突拒绝后仍可精确重放。

最终 Java 定向门 36/36 PASS（另有上述真实数据库 1/1 PASS），0 failure/error/skip：

```powershell
./mvnw.cmd "-Dtest=JpaAgentRunV4FinalizationFailureTest,JpaAgentRunLedgerProtocolTest,PostgresAgentRunV4EventWriterTest,IntakeParallelFrameAssemblerTest,AgentRunStreamEventServiceTest#rejectedV4FinalStaysHiddenWhileItsAdjacentSanitizedErrorIsReplayable+v4FinalRemainsHiddenUntilFormalCommit+matchingFormalCommitMakesV4FinalVisible+v4ReplayUsesAnAttemptScopedCursorAndPreservesTypedFramePayloads" test
./mvnw.cmd "-Dtest=AgentRunV4FinalizationRejectionIntegrationTest" test
python -m pytest tests/graphs/intake/test_parallel_outputs.py tests/graphs/intake/test_parallel_graph.py tests/harness/test_prompt_composer.py -q --disable-warnings -k 'parallel or dialogue or frozen_dossier or dossier or quality or frame'
```

测试编写期间修正过缺失 import、误用与 fixture 相同的“错误”hash、过窄的异常文案断言；
以上数字均为修正后实际执行结果，不使用中途失败结果充当通过。没有关闭正式校验或增加重试。

### 2026-09-06：冻结终审输入大小与输出边界分离

源码 `d02028cffd4123f865316292867392d0aaf8af85`、隔离 run `p9-b3dedde8706b`：

- 首案在无备注回复后出现 `GRAPH_PROVIDER_STREAM_INTERRUPTED`，仅 Dialogue frame 失败，
  Dossier/Quality 已 SEALED。已保留截图与数据库，不重试；现有日志无法进一步区分网络中断、
  缺失结束事件或 provider 终止原因，不能归因于 Schema，也不能宣称零模型/传输错误。
- 新表单创建 `CASE_P9_SYNTHETIC_2`，八次 Intake 均 COMPLETED/COMMITTED，双方邀请和
  无备注阶段事实矩阵分别保持不变；两份明确虚构的证据完成上传/解析，五项庭审焦点双方回答，
  未回答方看不到对方本轮内容。双方无补证后完成冻结、V1/评审/V2。
- 终审解释官 `AGENT_RUN_1ac15c9a6de545fea0e243ff75075ec0` COMPLETED/LEGACY_COMMITTED。
  人工明确纠正“放弃其他权利”等草案措辞，只批准冻结计划唯一 `TARGET_NO_EXTERNAL_EFFECT`，
  preconditions/notifications 均为空；不产生退款/补发或其他外部业务效果。
- ReviewTask `hearing-review-task-666c79f398573c7280472da3373501da` APPROVED，页面事件
  `ACT_ef48ac65ecfc789b30b39069bb1fc5fb`；但辅助运行
  `target-review-run:a637d299cf78395daaf01ba9e04ba93c` FAILED/UNCOMMITTED，故不计完整通过。

Python `outcome/state.py::_validate_request_binding` 在模型调用前抛出
`OUTCOME_REVIEW_REQUEST_TOO_LARGE`。冻结材料仅 draft/claims 等已超过 47KB，却复用输出的
32KiB 上限；Java 和 Python 既有签名 room-object 输入交换支持 512KiB。这是确定性边界错配，
不是本次模型抖动。修复独立设置输入 512KiB，仍按规范化 UTF-8 字节校验；完整材料不截断、
不重写 hash，输出保持 32KiB，正式权限/角色/引用/哈希/重放校验不变。

`python -m pytest tests/graphs/outcome -q --disable-warnings`：34 PASS。新增九个参数化回归
覆盖 48KB/512KiB 完整图执行、超界 UTF-8 拒绝、四种权限漂移拒绝、checkpoint 无敏感正文、
已投影重放零模型调用，以及输出仍超过 32KiB 即拒绝。48KB 正例在旧实现先真实复现同一异常。

本轮另外保留两项观察，不混称已修复：并发进入证据室时预期 revision 已被另一命令占用，
UI 把 admission 冲突显示为模型失败；确认无本方命令且对方提交完成后才重新进入。
终审/结果的通用“继续履约”显示与唯一 NO_EXTERNAL_EFFECT 计划不一致，不能从 UI 标签推导
实际外部执行；V2 模型建议仍需人工核实，没有绕过终审授权。

### 2026-09-06：Review 引用能力与命令完成所有权

源码 `12c01ff7231f03df1a09831b42c929f52deeeed0`、隔离 run `p9-2546417b6ab8`，
表单新建 `CASE_P9_SYNTHETIC_1`（测试订单 CUP-03）：8 次接待、4 次证据、7 次庭审运行
均 COMPLETED/COMMITTED；双方无备注阶段事实矩阵不变。用户与商家各上传一份明确虚构的
UAT 文字记录，完成五焦点回答、隔离可见性验证、无补证、V1/评审/V2。

本轮没有计为完整通过，也没有推送，终审发现两个独立问题：

1. 只读解释官把材料正文中的 remedy plan ID 当作 statement 引用，但该 ID 不在请求的
   available refs 并集中。静态输出 Schema 允许任意字符串，随后权限校验正确拒绝。
   修复给真实模型适配器提供请求专属引用目录、枚举 Schema 与明确提示词；空目录只能输出
   空引用。原始材料保留，持久化公共 contract 与最终 citation validator 不放宽。
2. 人工仅批准冻结计划唯一 NO_EXTERNAL_EFFECT 后，页面已到 Outcome，但辅助运行
   `target-review-run:a4e1d83d52423833bdfacc5d86c03722` 停在 RESULT_READY/UNCOMMITTED。
   38,891 字节冻结输入已通过并生成结果，证明输入边界修复生效。Java finalizer 随后试图用
   辅助 receipt hash 完成同一人工 command，而正式 Outcome owner 已写入另一 terminal hash，
   严格 ledger 以 COMMAND_COMPLETION_CONFLICT 拒绝。只读核验 case_command 为 APPLIED，
   result_sha256 精确等于已有 completion_hash。正确修复不覆盖/放松该 hash：明确 REVIEW
   与精确 graph/version/checkpoint 合同只验证 admission、自身回执完成；人工 command 仍由
   Outcome 或 non-execution disposition 唯一完成，且不依赖双方完成的先后顺序。

新增真实 PostgreSQL 事务测试先在旧代码复现相同 durable-binding conflict，以及辅助先执行
抢占 completion 的反向竞态。还覆盖 receipt room/pins/身份拒绝、精确重放、普通房间仍完成
agent receipt 并拒绝冲突、caller rollback/read-only 边界。数据库使用既有 PostgreSQL16
digest 的隔离测试容器；未修改正式 ledger、业务决策、迁移或任何历史行。

失败现场冻结备份已实际恢复验证 `1|2|21`，对象归档 372 entries，5 个归档 SHA-256 全部复核。
官方 teardown 仅移除本 run 的 23 containers / 14 networks / 8 volumes；旧主环境健康不变。
后续使用相同组件版本、统一固定启动流程重建，重新走完整浏览器验收；不得只凭 Outcome
页面显示成功认定通过，必须包含解释官及终审辅助结果的后台完成状态。

本次定向回归实际结果：Python 48 PASS、Ruff PASS；Java 13 PASS / 0 failure/error/skip
（其中真实 PostgreSQL 所有权测试 6 项），`git diff --check` PASS。执行命令：

```powershell
python -m pytest tests/agents/test_review_output.py tests/agents/test_review_copilot.py tests/graphs/outcome -q --disable-warnings
./mvnw.cmd "-Dtest=JdbcProductionCommandCompletionOwnershipTest,ProductionMultiRoomFinalizationGatewayTest,ProductionReviewRoomFinalizationStrategyAuthorizationTest,ProductionMultiRoomOuterFinalizerContractTest" test
```

### 2026-09-06：补齐 INITIAL_FORM 首轮阶段 Schema

下一轮 `ab079d24` / `p9-110400cb64bb` 的 CUP-04 首次表单运行被正确中止，未落正式卷宗：
`INTAKE_CONVERSATION_ACTION_PHASE_CONFLICT`（对外 GRAPH_STREAM_PROTOCOL_REJECTED）。
普通 ROOM_MESSAGE 已使用按上一阶段收窄的 ordered Schema，但 exact fresh form selector
和专用 LCEL 模型/解析器仍使用通用 `IntakeInitiatorRoomLlmOutputV3`，其中允许当前轮高分后
直接生成 INVITE_OPTIONAL_REMARK；初始状态机只允许 ASK_SUBSTANTIVE，因而拒绝。

修复复用既有 ASK_SUBSTANTIVE 分支 Schema，同时绑定 fresh form 的 request selector、
真实模型与 parser；ContextPack 明确提供可信首轮 NOT_READY/允许动作，提示词说明当前高分
不授权提前邀请。未改变状态机或历史 wire，未把模型非法动作改写为合法动作。

真实 Graph 新测试先证明旧 generic schema 接受提前邀请、随后复现相同阶段冲突；修复后
首轮生成终态、实际 provider Schema 排除邀请、非法结果在 parser 阶段拒绝且未进 reducer。
正例保留原请求与材料，schema 重放无额外模型调用。初始化测试时修正过缺失测试 context 与
fixture version pins，只有复现业务异常之后的 old-red 才作为机制证据。

定向 Python 99 PASS / 15 deselected（fresh form、ordered room、并行三分支、状态提示词），
Ruff 和 diff whitespace 检查通过。失败 UAT 已冻结恢复验证 `1|0|1`、对象 113 entries，
5 份备份 hash 复核后执行 exact run teardown；旧主环境不变。继续等待完整新案 E2E，未推送。

### 2026-09-06：最终真实模型浏览器 UAT 与后台收敛通过

被测代码为 `12a31175e0ad1e136fdf610ce07932e0d71aa30b`，本段仅补充验收记录，不改变被测代码。
隔离 run `p9-72cad5499c8a` 由固定 `tools/uat/production-runtime/start.py` 启动，构建目录
`builds/uat-12a31175-231a33471e6d`；模型为 `qwen3.8-flash`，thinking=false。沿用
Temporal 1.29.7 和 PostgreSQL 16 的固定镜像 digest，没有升级；旧主环境容器健康且未重启。
首次同版本构建遇到 Maven Central TLS 下载失败，重试同源码同依赖版本成功，未关闭 TLS 校验。

通过 Codex 内置浏览器在 `http://127.0.0.1:25180/disputes` 新建表单，订单引用
`UAT-20260906-CUP-05`，案件 `CASE_P9_SYNTHETIC_1`。这是蓝色杯/白色实收的虚构软件验收，
不是历史失败案件的重试；测试全程没有直接 API 写入、手工业务 DML 或重复提交最终决定。

| 验收环节 | 实测结果 |
| --- | --- |
| 首轮表单及双方接待 | 8 次运行全部 COMPLETED/COMMITTED；首次表单未提前邀请备注 |
| 按状态备注 | 双方均经历 READY_PENDING_REMARK_INVITE → WAITING_FOR_REMARK → NO_EXTRA_REMARKS；邀请和无备注阶段事实矩阵不变 |
| 双方证据 | 各上传一份明确标注虚构 UAT 的 TXT，经声明、上传、解析、本批提交、完成举证；4 次运行全部提交 |
| 庭审 | 根据实际生成的四个焦点逐项回答；商家提交前 DOM 不含用户独立回答，双方提交后共同公开；双方无其他补证 |
| 冻结与草案 | 冻结卷宗后完成 V1、评审、V2，7 次庭审运行全部提交；用户只读查看草案 |
| 终审解释官 | 针对证据局限、草案建议与 NO_EXTERNAL_EFFECT 区别提问，COMPLETED/LEGACY_COMMITTED；未复现非法引用 |
| 人工终审及辅助运行 | 仅批准冻结计划唯一无外部效果动作，辅助 REVIEW 运行 COMPLETED/COMMITTED；没有 completion hash 冲突 |
| 最终结果 | 案件 CLOSED/OUTCOME；用户和商家均看到同一最终 no-effect 事件，刷新未新增命令、运行或执行回执 |

接待阶段当场记录的事实矩阵文本 MD5 仅用于相等性对照，不代替协议 hash：用户版本 2/3/4
均为 `bbda4cbbc5b45f375a6fa3af4c2d0a8b`；商家版本 6/7/8 均为
`f339a02d1e0e82434a7a53214df5f2d5`。最终版本 8 的双方状态均为 NO_EXTRA_REMARKS。

两份材料：

- 用户 `cup05-order-receipt.txt`：`EVIDENCE_d0914f196f824763848a48276be0d0ba`。
- 商家 `cup05-warehouse.txt`：`EVIDENCE_2d133745787740138557e18efa4f7f21`。

最终只读持久态证明：

- Agent runs：20 个 COMPLETED/COMMITTED、1 个 COMPLETED/LEGACY_COMMITTED；未完成、
  未提交及非空 error_code 均为 0。21 条 case_command 全部 APPLIED。
- 解释官：`AGENT_RUN_abf975674ea748fb8e01823dbec712d6`。
- 终审任务：`hearing-review-task-4bc33a9690c83464a1890ad9b2da1c5c`，APPROVED，唯一记录。
- 辅助运行：`target-review-run:2ce31f78471c379ea3ea6ee728be180e`；REVIEW receipt 为 COMMITTED，
  receipt hash `70a1b2efc288b1ef61e038a57c281161077c293bffa7143c134e88c1959e0f90`。
- 人工命令：`review-decision:72b986640186c0955907ad4d4f62fcf98751aa18674fa3ff731d69f1c3d5d927`，
  APPLIED；`case_command.result_sha256` 和唯一 `production_runtime_command_completion.completion_hash`
  均为 `c41029648b5c1de05447e98c19930f0a0fdd5c56b1fd3f5689032fc253ac34ba`。
  它不等于辅助 receipt hash，证明双方完成所有权独立且均成功。
- 唯一 Outcome operation receipt：SUCCEEDED / JAVA_RECONCILIATION / SATISFIED；
  external receipt `target-noop:ORCT_bcbb9c2a2168267866c96da37e9db375`。
  用户、商家最终显示 `urn:target-outcome:no-effect:ACT_1d920b7578d53f63f84646eb0078cb78`。
- 刷新前后计数均为：21 runs、21 commands、20 finalization receipts、22 command completions、
  1 outcome receipt。未通过重发业务命令来做幂等测试；精确 replay 已由前述定向回归覆盖。

本地浏览器截图位于 `C:/Users/Jupiter/.after-sale-flow/production-runtime-local/p9-72cad5499c8a/evidence/`：
`user-remark-passed.png`、`merchant-remark-passed.png`、`hearing-answer-isolation.png`、
`user-v2-readonly.png`、`review-copilot-passed.png`、`reviewer-outcome-passed.png`、
`user-outcome-passed.png`、`merchant-outcome-passed.png`。截图与运行凭据保留在本地，
不将包含环境身份的启动文件或密钥提交仓库。

**结论边界：**本次通过的是单个完整新案的功能与后台收敛验收，不能推断模型永不抖动、所有
业务类型或全部测试套件通过。先前记录的基线测试失败没有在本轮掩盖或删除。V2 对虚构背景的
处理、证据表述以及通用“继续履约”展示仍有质量改进空间；人工已明确限制为测试结案、保留
用户权利，正式计划 actions 只有 TARGET_NO_EXTERNAL_EFFECT，preconditions/notifications 为空。
冻结 remedy_plan 的 PENDING_HUMAN_REVIEW 字段未改写，最终决定依据独立 review_task 和
正式 command/Outcome receipts，不据其单独推断运行未完成。
启动器原始 attestation 仍为 INFRASTRUCTURE_READY_ONLY / production_lane_runnable=false；
本功能记录不篡改其 business_e2e_passed 字段，也不替代剩余生产装配、权限与隔离认证。
