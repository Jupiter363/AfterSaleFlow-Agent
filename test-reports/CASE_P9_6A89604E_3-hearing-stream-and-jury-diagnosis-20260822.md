# CASE_P9_6A89604E_3 庭审流式误报与陪审展示缺项诊断报告

- 日期：2026-08-22
- 案件：`CASE_P9_6A89604E_3`
- 范围：庭审 M2 至法官 V2 的 AgentRun、案件时间线、SSE 订阅、庭审历史消息与陪审报告投影
- 结论等级：根因已确认

## 一、结论

页面出现的“流式连接失败（HTTP 500）”不是模型生成失败，也不是庭审业务流程失败。它是前端拿到一个公开的 AgentRun 启动通知后，错误地以商家身份订阅仅允许 SYSTEM/hearing-control 读取的内部 Agent 流所产生的旁路错误。

后台 Temporal 庭审编排不依赖浏览器是否成功读取 Agent 流。关闭“我知道了”只会清除前端本地错误弹窗，不会重试模型、取消后台任务或改变案件状态。因此后台仍按持久化阶段回执连续完成 M2、E1、E2、卷宗冻结、法官 V1、陪审评审和法官 V2。

用户看到的“报错后又复跑成功”实际包含两类完全不同的行为：

1. 前端对同一个无权读取的 SSE 地址最多重连 8 次；这是连接重试，不是模型重跑。
2. 后台在上一阶段正式提交后创建下一个阶段的新 AgentRun；这是正常流程推进，不是失败轮次重生。

数据库证明，本案所有庭审模型阶段都只有一次 attempt，全部一次完成并正式提交。

## 二、完整因果链

| 顺序 | 实际行为 | 结果 |
|---|---|---|
| 1 | Java 创建自动庭审 AgentRun | Run 的流受众固定为 `SYSTEM`，actor 固定为 `hearing-control` |
| 2 | Java 发布 `AGENT_RUN_STARTED` 案件时间线事件 | 该事件公开给 USER、MERCHANT、PLATFORM_REVIEWER、ADMIN，并携带内部 `stream_url` |
| 3 | `HearingCourtView` 收到事件 | 对所有支持的庭审 operation 无条件调用 `consumeHearingAgentRun` |
| 4 | 商家浏览器请求 AgentRun SSE | 请求携带 `X-Role: MERCHANT`、`Last-Event-ID: -1`、`Accept: text/event-stream` |
| 5 | Java 校验 AgentRun 流受众 | 正确识别为无权读取，底层真实错误是 `403 actor cannot read this agent run` |
| 6 | SSE 错误响应被内容协商扭曲 | 使用 `Accept: */*` 时可看到正确 403；使用前端真实的 `Accept: text/event-stream` 时变为空 HTTP 500 |
| 7 | 前端连接仓库重试 | `AGENT_STREAM_CONNECTION_FAILED` 被允许重连，默认最多 8 次，最后显示“流式连接失败（HTTP 500）” |
| 8 | Temporal 后台继续 | Agent 结果正式提交后，工作流按相邻阶段回执自动推进，与浏览器 SSE 无关 |
| 9 | 用户点击“我知道了” | `dismissStreamError` 只把 `streamError` 清空；案件事件流和后台编排仍继续运行 |

这解释了为什么错误具有稳定复现性：所有自动 Hearing run 都使用 SYSTEM 流受众，而每一条启动描述符都公开给庭审四类前端角色。它不是网络抖动，也不是某一次模型偶发输出。

## 三、为何后台能一直生成到裁决草案

本案数据库状态如下：

| 阶段 | Run | 完成时间 | Attempt | 正式状态 |
|---|---|---:|---:|---|
| Hearing 问题生成 | `target-hearing-run:198ec545942c32dfa4bd4af901204cd6` | 18:04:14 | 1 | COMPLETED / COMMITTED |
| M2 案情综合 | `target-hearing-run:4adf4cef52fe3e58a3c383ff76b40c42` | 18:09:28 | 1 | COMPLETED / COMMITTED |
| E1 补证请求 | `target-hearing-run:4377de1d2ba4370dbb3646192db4ceab` | 18:09:53 | 1 | COMPLETED / COMMITTED |
| E2 证据综合 | `target-hearing-run:a35291fd1d7f314986d3ff4d6a2c1a69` | 18:11:11 | 1 | COMPLETED / COMMITTED |
| 法官 V1 | `target-hearing-run:1c7890dfef213b30a1565bd0c56416d4` | 18:11:57 | 1 | COMPLETED / COMMITTED |
| 陪审评审 | `target-hearing-run:4b79e314838c38da97f33e80bde6c922` | 18:12:27 | 1 | COMPLETED / COMMITTED |
| 法官 V2 | `target-hearing-run:838c87d8bdd5327caefda9998c3023a1` | 18:13:28 | 1 | COMPLETED / COMMITTED |

已经持久化的正式产物包括：

- 法官 V1：`judge_proposal.v2`，包装载荷 10,447 字符；
- 陪审报告：`jury_review_report.v1`，结构化 proposal 3,786 字符；
- 法官 V2：`adjudication_draft.v3`，包装载荷 19,486 字符。

因此，“HTTP 500 后仍生成裁决草案”并不矛盾：HTTP 500 只发生在浏览器读取投影的连接上，业务写入和 Temporal 阶段推进早已在另一条持久化链路中成功执行。

## 四、陪审团为什么看起来没有完整输出

陪审模型实际完成了完整报告，正式 artifact 中存在：

- `findings`：6 项；
- `mandatory_revisions`：4 项；
- `public_message`：约 315 字；
- 完整结构化 proposal：约 3,786 字符。

显示缺项发生了两次：

1. Java 的庭审公共消息只发布 artifact 的 `public_message`，没有把 6 项 findings 和 4 项 mandatory revisions 投影到历史卡片；
2. 前端在该消息没有结构化 payload 时，把 315 字 fallback 再传入 `compactReportSection`，硬截断为 84 字。84 字又低于 1,500 字的“长报告”阈值，因此不会出现“查看完整长报告”按钮。

截图中结尾停在 `requ...`，正是第二次截断后的结果，不是模型输出在该处中断。

另外，当前卡片上的“中风险”和“75/100”不是本案陪审报告的真实字段绑定：在消息没有结构化 payload 时，前端函数直接返回这两个默认值。法官 V2 使用的是数据库中绑定的完整陪审 artifact，所以 V2 的 `review_responses` 已逐项回应陪审 findings 和 mandatory revisions；缺失只发生在用户可见投影。

## 五、被破坏的系统不变量

1. **发现权限与读取权限不一致**：公开时间线把内部流 URL 交给无权读取者，同时前端把“发现到 URL”误当成“具有读取权限”。
2. **业务状态与展示状态混淆**：只读 SSE 失败被显示成“数字人生成失败”，但实际 AgentRun 已成功提交。
3. **错误码被协议层改写**：真实 403 在 SSE `Accept` 下变成无正文 500，前端无法区分权限拒绝与服务故障。
4. **自动重连策略错误**：确定性的权限拒绝被当作暂态网络错误重试 8 次，形成稳定的延迟和重复误报。
5. **正式 artifact 与公共历史脱节**：陪审完整报告存在且被法官 V2 使用，但历史消息只有摘要，前端又进行二次截断并展示未绑定的默认指标。

## 六、建议修复顺序

### P1：先消除流式误报

1. 为 `AGENT_RUN_STARTED` 增加权威的可消费标识或流受众；SYSTEM-only run 不向案件参与者暴露可自动订阅的 `stream_url`。
2. `HearingCourtView` 只在当前 actor 属于流受众时订阅；发现描述符本身不得被当作授权。
3. Java SSE 入口在建立 emitter 前保留 401/403 的正确 HTTP 语义，不能因 `text/event-stream` 内容协商变成 500。
4. 前端对 401/403、无权读取和已完成内部 run 不重连；只对真正的网络中断或明确可重试故障重连。
5. Agent 流失败不得直接宣称“数字人生成失败”；应先读取 AgentRun 正式状态，已 `COMPLETED/COMMITTED` 时仅结束旁路展示。

### P1：恢复陪审报告可见性

1. 庭审投影提供经过公开规则筛选的结构化陪审报告，至少包含 6 项 findings、4 项 mandatory revisions 和真实风险/可信度字段。
2. 历史卡片默认展示完整 `public_message`，不要再将 fallback 固定截为 84 字。
3. 提供“查看完整评审报告”展开或抽屉，读取正式绑定 artifact 的公开视图。
4. 删除没有真实字段时的“中风险”“75/100”默认值；缺值应隐藏或明确显示“未提供”。

## 七、最小验收标准

- MERCHANT/USER 收到 SYSTEM-only `AGENT_RUN_STARTED` 后不调用对应 AgentRun SSE；
- 无权 SSE 请求稳定返回 403，不返回空 500；
- 同一权限错误不发生 8 次自动重连，也不出现“数字人生成失败”；
- 后台完成阶段后，页面能通过案件投影正常显示已完成状态；
- 陪审历史卡可查看完整公开摘要、6 项 findings 和 4 项 mandatory revisions；
- 风险与可信分必须来自持久化字段，缺值不伪造默认值；
- 法官 V2 继续绑定同一份完整陪审 artifact，父链 ID/hash 不改变。

## 八、关键代码证据

- `JdbcTargetHearingAgentRunStartedPublisher.java:25,33,133`：描述符声明“不授予 Agent 权限”，但公开给庭审角色并携带 stream URL；
- `TargetHearingInternalStageMaterializer.java:189`：内部 Hearing run 固定为 SYSTEM/hearing-control；
- `HearingCourtView.vue:2526,2876`：收到启动事件后无条件消费流；
- `agentStream.js:499,576,778`：连接失败默认重试 8 次；
- `sse.js:44,52`：请求固定使用 `Accept: text/event-stream`，非 2xx 统一显示连接失败；
- `AgentRunStreamEventService.java:310-322`：受众不匹配的真实错误是 Forbidden；
- `HearingCourtView.vue:1651,1686`：陪审 fallback 被压缩为 84 字；
- `HearingCourtView.vue:129,1315`：低于 1,500 字不会提供长报告展开。

