# Phase 1 经验快速参考

> Phase 2-8 日常开发先读本页。只有需要时间线、证据和完整根因时，才读取
> [`phase-1-retrospective.md`](./phase-1-retrospective.md)。

## 当前门禁

```text
engineering_checkpoint: PASS
promotion_gate: PENDING
next_phase_permission: BLOCKED
```

- Phase 1 代码与本地验证检查点已完成。
- `MIG-001` 未通过：仍缺真实 KMS、private ACL、synthetic driver、不可变证据存储和签名审批。
- 当前计划禁止进入 Phase 2。若要仅在 `OFF/SHADOW` 下继续开发，必须先批准 ADR 或计划变更。

## Phase 1 关键数据

- 墙钟时间：35 小时 16 分；排除最大无提交窗口后的可见会话约 12 小时 56 分。
- 核心变化：233 个文件，新增 36,658 行；测试约占新增量 44.8%。
- 最大问题提交：`41a0d419`，137 个文件、21,429 行，占新增量 58.5%。
- 历史浏览器基线到阶段末才发现漂移，首轮仅 `33/126`。
- Playwright 曾因本地 `ERR_NO_BUFFER_SPACE` 假失败，精确场景复跑通过。

## 七条经验

1. **控制 slice**：每阶段拆成 4-6 个纵向 slice；普通提交建议不超过 25 个文件或
   2,000 行手写变化，超出必须拆分或记录例外。
2. **先跑最小全链路**：第一个实现 slice 就要让 production-shape synthetic command
   走通合同、持久化、Workflow、projection 和恢复路径，不能阶段末才组装。
3. **配置必须测全上下文**：Spring profile、bean、scheduler、Worker registration 或配置属性变化，
   同一 slice 必须运行对应 `@SpringBootTest` context smoke。
4. **先认证基线**：复用同 commit 的绿色报告；报告缺失或不匹配时，先认证或登记已知失败再开发。
5. **聚焦验证**：slice 内只跑相关 L0/L1/L2；全仓与浏览器全量只在约定统一检查点运行一次。
6. **先分类再复跑**：失败先归类 PRODUCT、FIXTURE、INFRA 或 EXTERNAL_GATE，保存证据后只复跑必要范围。
7. **分开报告状态**：任何阶段报告都同时给出 engineering checkpoint、promotion gate 和
   next-phase permission，禁止用一句“已完成”代替。

## 开始前

- [ ] 阅读本页、目标 Phase 计划、上一阶段 evidence 和当前功能基线。
- [ ] 确认上一阶段三项状态及正式进入条件。
- [ ] 记录 branch、HEAD、开始时间和工作区状态；保留无关变化。
- [ ] 验证基线报告与当前 commit 一致，登记既有红项。
- [ ] 定义 4-6 个纵向 slice、唯一 owner、文件边界、合同、回滚点和聚焦命令。
- [ ] 把 KMS、ACL、审批、soak、PITR/DR 等外部门禁单独列出，不计入编码工时。

## Slice 内

- [ ] 先写失败合同或测试，再做最小实现。
- [ ] 一次只推进一个可独立验证的闭环，不同时切两个 room writer。
- [ ] 配置变化立即跑 context smoke。
- [ ] 聚焦测试和独立 review 通过后，才开始依赖 slice。
- [ ] 自动记录命令、起止时间、退出码、JUnit/trace 路径和 SHA-256。

## 阶段边界

- [ ] 固定唯一 candidate commit，确认工作区干净。
- [ ] 验证 writer、epoch、fence、idempotency、retry、replay 和 rollback 不变量。
- [ ] 运行阶段要求的合同、恢复、数据库和服务集成测试。
- [ ] 统一检查点失败时先分类，不无条件重复全量。
- [ ] 从同一 commit 生成 evidence bundle 和 `phase-metrics.json`。
- [ ] 明确输出三项状态；外部门禁未完成时 promotion 保持 `PENDING`。

## 故障处理

| 类型 | 处理 |
| --- | --- |
| PRODUCT | 修代码并跑受影响测试；阶段边界再跑规定集合 |
| FIXTURE | 先证明产品合同，再更新 fixture；不得降低安全断言换绿色 |
| INFRA | 保存 trace，清理环境，只复跑失败场景；不得修改产品代码迎合环境 |
| EXTERNAL_GATE | 工程结果可保留，promotion 必须保持 PENDING |

目标：后续相似阶段的本地工程检查点控制在 7-9 个有效工作小时；外部审批和生产演练独立计时。

## 详细资料

- [Phase 1 完整复盘](./phase-1-retrospective.md)
- [Phase 1 MIG-001 手册](./phase-1-mig-001.md)
- [总重构计划](../../../plans/temporal-langgraph-room-refactor.md)
- [生产验证清单](../../acceptance/temporal-first-agent-platform-verification-checklist.md)
