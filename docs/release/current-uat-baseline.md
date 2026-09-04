# 当前 UAT 基线

- 文档日期：2026-09-04
- UAT 运行代码提交：`10526e58b954498f69bae00ea709f6f9e4981971`（生产 clean break 前）
- UAT 后首个 README 同步提交：`f5cb06864272a10da8c893feec036265322242e5`
- 浏览器 fresh case：`CASE_P9_6A98633E_11`
- 结果：六站流程完成，进度 `6 / 6`

该案件证明 V4 并行 Graph 的业务行为能够完成六站流程，但不证明重命名后的生产构件
字节身份。Production Runtime 发布必须以实际 `main` commit、构建 artifact 和 activation
hash 重新绑定，并使用全新数据库与 Temporal namespace，不能复用该案件的持久状态。

## 版本身份

| 维度 | 当前 UAT 代码基线 |
| --- | --- |
| 生产 Contract Baseline | `production-contract-baseline.v1` |
| Graph key | `all-rooms.production-runtime.v2` |
| Graph version | `production-runtime-graph.2026-08-18.3` |
| Checkpoint | `production-runtime-checkpoint.v2` |
| Intake profile | `PARALLEL_FRAMES_V1`；生产 clean break 不接入旧 `MONOLITHIC_V3` UAT History |
| Intake stream | `agent-stream.v4` |
| Evidence / Hearing / Outcome stream | `agent-stream.v3` |
| Evidence | `evidence_room_context.v2` / `evidence_turn_stream.v3` / `evidence-turn-result.v3` |
| 模型 | `qwen3.8-flash`；thinking 关闭；strict JSON Schema |
| Domain migration | Flyway `V094` |
| Graph migration | `G017` |

## 已验证行为

1. 从真实前端表单创建 fresh case，不通过调试接口预写案件状态。
2. USER 与 MERCHANT 的私有 Intake 均由 V4 三 Frame 路径完成。
3. `DIALOGUE_FRAME`、`DOSSIER_FRAME`、`QUALITY_FRAME` 独立生成/校验，由 Java exact-three
   admission、deterministic assembly 和 Finalizer 形成唯一正式结果。
4. `next_verification_focus` 以面向用户的中文核验动作呈现，不暴露内部英文字段名。
5. Evidence 覆盖授权图片/文档的来源绑定，随后进入 Hearing、人工 Review 和 Outcome。
6. 流程中未依靠手工数据库更新、重复提交已接受命令或绕过人工终审完成推进。

## 平台边界

Contract Baseline v1 是统一发布目录，不会把 `agent-stream.v4`、`hearing_flow.v2` 等
持久化协议机械改名为 v1，也不会移除版本 discriminator。完整目录和演进规则见
[生产合同目录](../contracts/README.md)。

该次 deployment-pinned 路由验证运行于经单独授权升级并验收的 Temporal Server
`1.29.7`。仓库 Compose 默认仍为 `temporalio/auto-setup:1.25.2`，本页不会也不能授权
任何启动脚本自动升级 Temporal、数据库或其他核心组件。

UAT 成功证明业务路径可完成，不等于当前生产重构构件已发布，也不替代：

- 全仓静态、Java、Python、OCR、前端和 Compose 检查；
- 新生产 namespace 上的 synthetic routing、Worker versioning 与 fresh-case 门禁；
- 生产容量、安全、备份恢复、监控和回滚证据；
- 正式镜像、配置、密钥、数据库 migration 与 activation 的同一提交绑定。

## 权威入口

- [接待室 V4 架构](../architecture/intake-room-context-and-streaming.md)
- [证据室上下文与来源绑定](../architecture/evidence-room-context-binding-and-token-streaming.md)
- [Temporal-first 总架构](../architecture/temporal-first-agent-platform.md)
- [生产验证清单](../acceptance/temporal-first-agent-platform-verification-checklist.md)
- [Canonical 回归夹具](../acceptance/canonical-full-chain-uat-fixture.md)
- [部署说明](../deployment/README.md)
