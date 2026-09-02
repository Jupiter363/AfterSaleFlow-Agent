# V4 并行图 UAT 候选版（2026-09-02）

## 版本身份

- 候选分支：`codex/v4-parallel-graph-uat-20260902`
- UAT 源码快照：`5365e2b205828eb1bfed9477fb83484d74d7bd42`
- 快照基线：`60e4aa3c53f220247f94fe62f32c2c4af470af17`
- 数据库迁移上限：`V093__target_e2e_graph_patch_release_identity.sql`
- GitHub `main` 原状态备份：`codex/main-backup-20260902-fd242cbe`

## 本次候选内容

- 接待室 Intake V4 采用三个显式兄弟节点并行生成对话、卷宗和质量框架，并在 Java 侧按冻结权威合并。
- 补全跨 Python、Java、Temporal、数据库投影的命令、事件、租约、最终化、重放和恢复边界。
- 为 CaseProcess 与 IntakeRoom 增加精确 execution re-pin/recovery 工具及 fail-closed 回归。
- 收窄恢复 Worker 的注册范围，保持 Workflow/Activity task queue 与 deployment version 权威一致。
- 接待提示词要求“下一步核验重点”输出面向用户的中文核验动作，不再暴露内部英文字段名。
- 修复新建案件成功后创建弹窗继续遮挡 Intake 准备失败恢复入口的问题。
- 补全证据房、庭审、审核解释和最终结果页在本轮 UAT 中发现的契约缺口。

## 浏览器 UAT 结果

使用 `http://127.0.0.1:5173` 的真实前端表单创建全新案件，未通过后端接口预置案件。

- 案件：`CASE_P9_6A9709FA_11`
- 订单：`ORDER-E2E-20260902-06`
- 商品：便携榨汁杯，争议金额人民币 168 元
- 用户、商家两条 Intake 分支均完成，完成度达到 100%，核验重点为中文自然语言。
- 用户与商家均通过浏览器上传并提交合成 UAT 材料；系统按低置信度/需人工核验处理，符合材料真实性边界。
- Evidence 封卷后完成双方庭审问答、补证声明、共享证据整理、卷宗冻结、法官 V1、陪审复核和法官 V2。
- 平台审核员批准附条件退货退款，生成执行事件 `ACT_c449589dad0fd1aec3ae55721465485d`。
- 用户端结果页显示“退货退款已完成”，总览显示进度 `6 / 6`。

## 提交前最小门禁

- `java-api-service`: `mvnw.cmd -DskipTests test-compile` — PASS。
- `python-agent-service`: V4 三兄弟图结构、三输出契约、中文核验提示词 3 个决定性节点 — `3 passed`。
- `frontend`: 新建案件提交后关闭弹窗并仅重试准备流程的决定性节点 — `1 passed`，其余 39 个节点按选择器跳过。
- 内置浏览器完成上述 fresh-case 全链路 UAT。
- `git diff --check` — PASS。

本候选没有重新运行全仓 release gate；各机制修复已在开发过程中执行对应聚焦回归，本记录只声明上述最终快照门禁和浏览器 UAT。

## 运行平台与边界

- 浏览器 UAT 时 Temporal Server 为 `1.29.7`，镜像摘要 `sha256:f14912b699cf73015ad5c4fc18d522d4b014db90e794039214dfb7c022c2644f`。
- PostgreSQL Temporal core schema 为 `1.18`，visibility schema 为 `1.9`。
- 本候选包含 deployment/version dynamic-config 权威，但不包含自动升级 Temporal 或其他核心组件的动作。
- 后续核心组件升级必须取得单独明确授权。

## 本地运维产物

下列文件受 `.gitignore` 的 `.local-dev/` 规则保护，没有进入 GitHub 源码提交；保留哈希用于核对本次本机 UAT 环境，且不得视为可移植发布产物：

- `.local-dev/launch-source.ps1`: `E612847C57382C699AB035E766D808691B31A6D75F6FF21BAEF1758080C4F80C`
- `.local-dev/operator/StartPinnedControlWorker.ps1`: `AE73259FEFF2A46ECB35A0A8A386CAA128D6E27BFA68AB77874434D4B0CE1E14`

合成证据、运行日志、数据库备份、Temporal 历史导出、凭据和本机激活文件均未进入本次提交。
