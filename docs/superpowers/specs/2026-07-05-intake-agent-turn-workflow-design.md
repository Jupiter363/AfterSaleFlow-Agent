# 接待官实时 Agent 房间设计规格

## 目标

把争议接待室从“留言板 + 手动确认”升级为“大厅表单首轮驱动 + 接待官客服式追问 + 右侧卷轴 Skill 绘制 + 数据库记忆”的 AI Native 房间。

本阶段先搭骨架，不设计复杂 prompt，不接真实知识库插件，不做复杂动画。

## 核心交互

1. 用户在争议大厅提交表单。
2. Java 创建争议单、打开 INTAKE 房间，并把大厅表单保存为首轮 seed。
3. Java 以服务端身份调用 Python `/internal/agents/intake/turn`。
4. Python 用 LangGraph 跑一轮 `IntakeTurnGraph`：
   - 加载上下文
   - 识别用户意图
   - 运行接待推理
   - 预留知识库问答节点
   - 调用卷轴绘制 Skill
   - 校验输出
5. Java 保存接待官回复、卷轴快照和本轮记忆。
6. 前端通过 SSE 刷新左侧对话和右侧卷轴。
7. 用户继续发消息即默认继续补充，不设置“继续补充”按钮。
8. 右侧只保留两个决策按钮：
   - 问题已解决，取消争议
   - 提交争议审理

## 系统边界

### Java 负责

- 前端唯一入口。
- 身份、权限、幂等、审计。
- 房间消息不可变写入。
- 案件状态流转。
- `room_turn_memory` 记忆落库。
- SSE 推送。
- 取消争议、提交争议审理的最终业务生效。

### Python 负责

- LangGraph 一轮 Agent 编排。
- 接待官推理。
- 知识库问答扩展口。
- Dossier Canvas Skill。
- 输出结构化建议和卷轴绘制指令。

Python 不直接修改案件状态，不直接写业务库。

## IntakeTurnGraph

节点：

```text
START
  -> load_context
  -> classify_intent
  -> intake_reasoning
  -> knowledge_qa_stub
  -> dossier_canvas
  -> validate_output
  -> END
```

第一版 `knowledge_qa_stub` 只标记用户是否有知识库问答意图，并输出固定客服式提示；后续统一替换为 RAG/MCP 插件。

## Python 接口

`POST /internal/agents/intake/turn`

请求：

```json
{
  "case_id": "CASE_xxx",
  "room_type": "INTAKE",
  "turn_source": "LOBBY_SEED",
  "lobby_seed": {
    "order_reference": "ORDER_123",
    "after_sales_reference": "AS_456",
    "logistics_reference": "SF_789",
    "initiator_role": "USER",
    "raw_text": "物流显示签收但我没收到，希望补发",
    "requested_outcome_hint": "RESHIP"
  },
  "current_user_message": null,
  "latest_scroll_snapshot": null,
  "recent_turns": []
}
```

响应：

```json
{
  "room_utterance": "我看到你填写的是签收未收到问题。请补充是否有签收截图或快递柜取件记录。",
  "dossier_patch": {
    "order_reference": "ORDER_123",
    "party_claims": {
      "user": "物流显示签收但用户未收到"
    },
    "requested_outcome": "RESHIP",
    "missing_initial_fields": ["DELIVERY_PROOF"]
  },
  "scroll_snapshot": {
    "cards": []
  },
  "canvas_operations": [],
  "admission_recommendation": "NEED_MORE_INFO",
  "missing_fields": ["DELIVERY_PROOF"],
  "knowledge_query_intent": false,
  "confidence": 0.74
}
```

## 数据库记忆

新增表：`room_turn_memory`

核心字段：

- `id`
- `case_id`
- `room_type`
- `turn_no`
- `actor_id`
- `answer_role`
- `answer_content`
- `agent_role`
- `agent_response`
- `dossier_patch_json`
- `scroll_snapshot_json`
- `canvas_operations_json`
- `agent_run_id`
- `created_at`

约束：

- 用户/商家行记录回答内容。
- 接待官行记录 Agent 回复、卷轴补丁、卷轴快照。
- 下一轮输入使用最新接待官行的 `scroll_snapshot_json` 作为右侧记忆。

## 前端接待室

接待室分为：

- 左侧 `ConversationStream`
- 右侧 `IntakeDossierScroll`
- 决策区：
  - 问题已解决，取消争议
  - 提交争议审理

发送消息后：

1. 立即保存用户消息。
2. 显示“小衡正在整理卷宗…”。
3. 接收接待官消息和卷轴事件。
4. 右侧按 `canvas_operations` 更新。

## 不做范围

- 不实现真实知识库/RAG。
- 不设计最终 prompt。
- 不把 Python 输出直接作为业务状态生效。
- 不让浏览器直接调用 Python。
- 不添加“继续补充”按钮。
