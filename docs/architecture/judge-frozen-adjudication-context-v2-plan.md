# 法官冻结裁判上下文 V2 对齐计划

## 目标协议

`trial_dossier.v2` 是法官、陪审评议和复审链路绑定的唯一冻结卷宗。其可裁判内容只包含：

1. 最终 M2 `case_fact_matrix.v2`；
2. 最终 E2 `fact_evidence_matrix.v3`；
3. 冻结时绑定到本案的完整 `adjudication_rules` 快照。

问答集、回答包、争点迁移/状态、补证请求和补证批次只属于上游生产过程，不进入 V2 卷宗的可裁判内容。卷宗 ID、矩阵版本/哈希、冻结时间和卷宗哈希只用于后端持久化、绑定、幂等与重放，不进入模型可见上下文包。

## 模型上下文装配

共享裁判核心规则单独维护，负责：事实认定、事实与证据绑定、规则条件审查、救济推导、不得越过冻结材料、草案非终局等共同约束。

- V1 装配：`共享裁判核心规则 + V1 生成规则 + frozen_adjudication_context + decision_action_catalog`。
- V2 装配：`共享裁判核心规则 + V2 复审规则 + frozen_adjudication_context + v1_draft_pack + jury_opinion_pack + decision_action_catalog`。

`decision_action_catalog` 始终作为模型输入的最后一个上下文区块，以提高最终收束时的注意力；它是通用输出词表，不属于案件事实、证据或冻结裁判规则。

V1 装配器不得出现陪审或 V2 复审规则。V2 装配器不得出现 V1 生成指令；V1 草案只作为复审对象数据。陪审意见只能指出、评价或建议，不能把矩阵外的新事实、证据或规则带入 V2。

## 输出协议

V1 与 V2 共享同一个完整裁决草案主体：

- `remedy_orders`
- `fact_findings`
- `rule_applications`
- `decision_reasoning`
- `reviewer_attention`
- `decision_action`

`decision_action` 必须且只能选择 `CANCEL_ORDER`、`RETURN_AND_REFUND`、`REFUND_ONLY`、`RESHIP`、`REPLACE`、`REPAIR`、`COMPENSATE`、`CONTINUE_FULFILLMENT`、`REJECT_CLAIM` 之一。旧的自由文本 `recommended_decision` 不再输出；编码的简明业务含义由输入末尾的 `decision_action_catalog` 提供。

V1 额外输出 `review_focus`。V2 额外输出逐项带采纳结论和理由的 `review_responses`；V2 的 `reviewer_attention` 即复审后剩余人工关注事项，不再增加同义字段。

模型只生成结构化草案。面向消息、审核工作台和既有数据库投影的正文由后端从该结构确定性渲染，避免 `public_message`、`draft_text` 与结构化内容形成多个事实源。

## 后端迁移

1. 新增 `trial_dossier.v2` 及数据库兼容迁移；既有 V1 历史行只读保留，新冻结一律写 V2。
2. 两个卷宗生产入口统一生成相同 V2 结构，并校验 M2/E2 互绑、E2 已冻结、规则版本唯一和卷宗哈希。
3. Judge V1/V2 请求继续携带后端绑定元数据，但模型调用前按阶段白名单裁剪。
4. Judge V1 升级为 `hearing_judge_v1.v2` / `judge_proposal.v2`；Judge V2 升级为 `hearing_judge_v2.v2` / `adjudication_draft.v3`。
5. 现有人工审核投影继续使用原表，通过确定性兼容投影填充正文、规则适用和证据评估列；正式 artifact JSON 保存完整新结构。
6. 数据库父链约束、Java 形式化校验、重放查询和审查交接查询同步接受并要求新版本。

## 验收

- 卷宗 V2 不含上游问答、争点过程或补证过程正文。
- V1 模型数据只有冻结裁判包；V2 模型数据只比 V1 增加 V1 草案包和陪审意见包。
- V1/V2 的系统规则由不同装配白名单组成，测试证明互不串包。
- 所有 `fact_findings.fact_id` 来自 M2；证据引用必须来自该 fact 的 E2 绑定；规则引用必须来自冻结规则快照。
- V2 对陪审修订意见逐项给出采纳、部分采纳或不采纳及理由。
- 后端聚焦测试通过后，从新案件完成卷宗冻结、V1、陪审、V2、人工审核和下游 mock 调用；随后通过指定浏览器 CDP 完成同一路径前端 UAT。
