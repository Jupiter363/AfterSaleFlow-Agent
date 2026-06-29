# 架构说明

权威需求来自 `Project Plan` 中的主控开发文档、统一配置说明和最终验收清单。

系统保持以下不可绕过的链路：

```text
Case Intake
  -> Evidence Dossier
  -> Router
  -> A/B/C 业务路径
  -> D Remedy Planner
  -> Approval Policy
  -> Platform Human Review
  -> Tool Executor
  -> Case Closure
  -> Offline Evaluation
```

- Java + Temporal 控制全局状态、等待、Signal、审批和执行。
- Python + LangGraph 只分析证据并生成非最终草案。
- Agent 不直接执行退款、补发、关闭售后等动作。
- Tool Executor 只执行审核后快照中的动作。
- Evaluation Agent 只分析 closed case，不改变在线 Case、规则或 Prompt。
