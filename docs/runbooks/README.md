# 生产运行手册

本目录只保留仍对应当前生产架构的恢复、安全与灾备手册。文件名中的 `phase-8` 是已部署
监控资产和告警锚点的一部分，为保持 Dashboard、Alert 和外部引用稳定而保留；它不表示
系统仍处于 Phase 8，也不构成待完成的开发计划。

## 当前手册

- [告警响应](temporal-first/phase-8-alert-response.md)
- [Domain PostgreSQL PITR](temporal-first/phase-8-domain-pitr.md)
- [Graph 对象恢复](temporal-first/phase-8-graph-object-restore.md)
- [密钥与版本轮换](temporal-first/phase-8-rotation.md)
- [安全加固](temporal-first/phase-8-security-hardening.md)
- [Temporal 区域灾备](temporal-first/phase-8-temporal-regional-dr.md)

## 使用边界

- 当前应用与协议身份以[当前 UAT 基线](../release/current-uat-baseline.md)为准。
- 本地 Compose 的默认 Temporal 镜像与曾经单独授权的 UAT 平台版本必须区分；详见
  [Temporal 部署边界](../deployment/temporal.md)。
- 运行手册不授权核心组件升级、生产切流、数据库恢复或外部高影响动作。这些操作必须有
  独立审批、备份/回滚证据和执行窗口。
- 模型输出、Graph checkpoint 或前端状态都不能替代 Java Domain 账本、Temporal History
  与正式人工终审。
