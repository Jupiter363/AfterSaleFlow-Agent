# 前端模块说明

前端只负责交互、权限投影和可恢复状态展示，不承载裁决、审批或执行规则。

| 目录 | 职责 |
| --- | --- |
| `frontend/src/components/agent` | 安全展示 Agent 进度、Trace、引用、中断和降级状态 |
| `frontend/src/components/evidence` | 证据目录、不可变时间线、事实矩阵、预览和缺口/冲突 |
| `frontend/src/components/hearing` | 庭审阶段、非最终草案标识、修订历史和合议意见 |
| `frontend/src/components/review` | 冻结 ReviewPacket 与角色受限的人工作出决定；生成式 UI 不得审批或执行 |
| `frontend/src/components/shared` | 可访问的加载、错误、空态、确认和来源引用组件 |
| `frontend/src/router` | 争议与复核工作区路由；已移除旧 `/cases` 路由 |
| `frontend/src/schemas` | API 响应及生成式 UI 组件/动作白名单的运行时校验 |
| `frontend/src/stores` | 案件、卷宗、庭审、复核和 AgentRun 状态隔离 |
| `frontend/src/views/disputes` | 接待、案件工作区、证据、庭审、草案与结果页面 |
| `frontend/src/views/reviews` | 平台审核队列和冻结 ReviewPacket 工作台 |

具体实现以对应目录中的 Vue、TypeScript/JavaScript 源码和测试为准。

## 当前 UAT 展示合同

- Intake 新流程消费 `agent-stream.v4` 的三个 Frame，并只在 Java 已确认 final authority 后更新正式卷宗。
- Evidence、Hearing、Review 与 Outcome 继续消费各自的 `agent-stream.v3`/正式 Java 投影。
- “下一步核验重点”来自模型拥有的 `next_verification_focus` 业务语义。Prompt 与 Schema 要求其为
  “核验/核对/确认 + 具体对象 + 待核验事实”的简体中文动作；前端不得把内部字段名当正文展示，
  也不得用 UI 映射重新定义模型语义。
- 前端可以对历史/回放数据做无损的防御性可读化，但机器标签、JSON 路径、隐藏推理和 raw
  provider payload 均不能进入公开页面。

当前浏览器基线及其边界见[当前 UAT 基线](../release/current-uat-baseline.md)。
