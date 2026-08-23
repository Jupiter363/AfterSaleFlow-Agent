# 庭审案情接待官上下文、分层流式输出与 M2 生成重构计划

状态：V4 机制重构已实现并完成 M2 局部链路验证（未部署）

冻结日期：2026-08-19

局部验证日期：2026-08-19

适用范围：从前端/HTTP 回答提交、Java action 与 Temporal 状态推进、Target invocation、Python Context/Schema/Graph、agent-stream.v3 帧桥、Java 正式化与数据库约束、公开消息提交、查询投影、幂等重放，到 `case_fact_matrix.v2` 后继矩阵 M2 和 `hearing_issue_state_set.v4` 正式落库的完整链路

明确不包含：庭审证据官读取 M2 与庭前证据矩阵 E1、生成补证请求、形成 E2 的语义交接协议；该部分在庭审接待官稳定生成 M2 后另行设计。由于现有状态机会在 M2 后自动启动证据官，新 activation 在该下游合同升级前不得部署或接入正式 Hearing workflow。本轮只运行仓库内 focused/unit/contract/transaction integration tests，不创建 UAT 案件、不操作浏览器、不执行运行态 stage 推进

不改变：系统安全提示词、数字人提示词、共享庭审状态机的阶段顺序、AgentRun/command/epoch/fence/幂等权威、人工审核与裁决链路

实施验证结论：正式 M1 可经过 QUESTION V4、双方有序 ANSWER BUNDLE V4、ANSWER SYNTHESIS V4，形成通过 RFC 8785/JCS 自哈希校验的 M2 与 `hearing_issue_state_set.v4`，并完成正式 frame/message/receipt 绑定与幂等重放。Java 聚焦链路 46 个测试、Python V4/streaming 5 个测试以及 PostgreSQL 16/Flyway V070 专项迁移测试均通过；Java `test-compile`、Python Ruff 与 `git diff --check` 通过。本结论不表示部署或 UAT 放行，M2 后庭审证据官仍保持封闭。

## 1. 文档目的

本计划用于替换当前庭审案情接待官的 V1/V3 混合实现，统一定义：

1. Java 如何从正式状态、M1、问题集和双方终态回答装载唯一输入；
2. Python 如何按固定顺序装配 `QUESTION_GENERATION` 与 `ANSWER_SYNTHESIS` 两种业务上下文；
3. 模型如何一次调用同时生成公开回复和无歧义结构化绑定；
4. 如何只让公开回复逐 Provider delta 输出，而允许后续结构字段聚合到完整值或终态；
5. 后端如何只校验 Schema、ID、角色、来源、顺序、预算、幂等、重放和状态机，不使用正则或第二套语义逻辑改写模型内容；
6. 如何要求双方对每个正式争议点提交非空本轮回答，并由模型只依据本轮两方回答重新绑定、重判当前争议状态；
7. 如何把重判后的争议状态投影为诉求、回应、事实和关系变化，并从 M1 确定性生成可追溯、可重放、可正式化的 M2；
8. 如何在 Provider 中断、流式已公开、终态失败或正式提交重试时避免重复消息和重复模型调用。

本计划采用新 activation 单版本切换。庭审案情接待官 V4 的生产者、模型合同、Python Graph、Java 正式化和前端流式消费者同步升级；不保留庭审接待官 V1/V3 的运行时兼容分支，不恢复旧 checkpoint，不在旧失败 AgentRun 上重放。

### 1.1 术语与本轮测试切片

- **M1**：庭审案情接待官开始工作时唯一读取的庭前冻结 `case_fact_matrix.v2` 父矩阵，包含既有案情、claims、fact rows、双方位置和 alignment；M1 不是证据原件，也不是证据矩阵。
- **E1**：庭前证据官形成的冻结 `fact_evidence_matrix`，描述证据与事实之间的支持/反驳/覆盖关系；E1 在后续庭审证据官阶段使用，本轮庭审案情接待官不得读取证据原件或用 E1 作真伪判断。
- **M2**：基于 M1、正式问题集、双方逐 issue 本轮回答和模型绑定结果，由后端确定性物化的下一版 `case_fact_matrix.v2`；其 version 为 M1+1，parent_ref 精确指向 M1，并与最终 issue-state、公开 frames 和正式 receipt 原子绑定。

本轮局部测试只打通：`正式 M1 authority -> QUESTION -> 双方 V4 answer bundles -> ANSWER 绑定解析 -> canonical issue transitions -> M2 -> Java 正式化/读取/重放`。测试夹具可证明 Hearing prelude 已完成且 E1 已冻结，但不把 E1 语义接入本轮模型，也不继续启动庭审证据官。

## 2. 当前实现核对结论

### 2.1 已有可保留机制

- Java 已从正式 Hearing prelude 读取庭前冻结案情矩阵 M1，并在问题生成阶段把 M1 交给 Python。
- Java 已要求 `INTAKE_SYNTHESIZING` 的结果矩阵版本等于 M1 版本加一，且 `parent_ref` 精确绑定 M1 的 ID、版本和 hash。
- Python 当前合并器已能保留旧事实 ID 与顺序、拒绝修改旧事实的 `category/fact_target/materiality`、为新事实生成稳定 ID、生成 M2 的版本、父引用、来源、索引和内容 hash。
- 当前模型传输本身支持原生异步结构化 SSE，底层增量 JSON scanner 能按字符串前缀产生可见 delta。
- 当前 Target Hearing 网关已校验 activation、graph、model profile、prompt profile、output schema、policy、fence 与 command request hash。
- Java 正式 question set 已能生成稳定 `question_set_id/question_id/issue_id`，并把双方角色固定为 USER 与 MERCHANT。

### 2.2 当前阻塞与错误边界

| 边界 | 当前状态 | 需要重构的原因 |
|---|---|---|
| Hearing LCEL | V3 ContextPack 已开始装配，但未把可信 `AgentInvocationContext` 传入 Harness | 已确认会使 signed traceparent 在内层丢失，并在 Provider 调用前失败 |
| 问题模型输出 | `public_message + questions` | 只有单一公开字段，没有问题帧、顶层 manifest 和帧级重放身份 |
| 综合模型输出 | `public_message + hearing_case_fact_matrix.delta.v1 + issue_mappings` | 中间合同与接待室矩阵 delta 不统一，不支持诉求变化、回答单元绑定和完整争议状态变更 |
| Java answer 输入 | SQL 已读取 action `id/content_hash`，但 `partySubmissions` 只传 payload | 模型无法引用正式 `answer_bundle_id` 与内容 hash，只能依赖无稳定身份的嵌套文本 |
| 回答 HTTP 合同 | 同时接受 `hearing_answer_bundle.v1` 与自由文本 `hearing_party_statement.v1`，前端实际走 `/statements` | 自由文本可绕过逐 issue 完整覆盖，不能作为 V4 当前立场权威 |
| 回答截止时间 | Java legacy 与 Target/Temporal 都把 `AUTO_TIMEOUT` 写成 `ANSWER_BUNDLE`，第二个终态会自动推进综合 | 超时会被伪装成回答并触发模型，违反“双方向每个 issue 提交非空本轮回答” |
| Action 数据库约束 | `hearing_flow_action` check constraint 与 `HearingFlowActionType` 硬编码 question/answer V1 | 只改 Java/Python DTO 会在正式写入时被数据库拒绝 |
| M2 claims | 合并器直接复制 `previous.claims` | 双方庭审中修改诉求或回应时，M2 仍保留旧状态 |
| respondent source | `respondent_direct.source_type` 仅允许 `RESPONDENT_DIRECT_INTAKE` | 无法准确表示庭审中的本人直接更新 |
| 争议状态 | 旧 `_hearing_alignment` 依据 stance、asserted value 和少量字段机械推断 | 容易在双方都回答但值未标准化时误判；与“信任模型业务语义”口径不一致 |
| Streaming registry | Hearing 只登记 `public_message` | 无法支持 `lead_public_text`、单一 manifest 和多个 public frame text |
| Target stream bridge | 只接受 `field=public_message`，终态要求所有 delta 等于单一字段 | 无法表达帧身份、帧级完成与多段公开文本 |
| Target invocation decoder | 同时保留 fixture v1/governed v2，且重新构造 `HearingGraphInvocation` 时丢弃调用 sidecar | 新 activation 若继续双解码或复制 invocation，V4 权威仍会在 Python Provider 前丢失 |
| Java transcript | 终态把 `public_message` 与问题列表重新拼成一条消息 | 会形成第二套公开文本并破坏同源逐 delta/帧级持久化 |
| 聚合器 | 通用 V2 coalescer 允许最多 75ms/4KiB 合并相邻 delta | 庭审公开回复路径若经过该层，会增加人为等待并削弱真实流式观感 |
| 帧持久化 | 仓库已有 agent-stream.v3、`agent_run_public_frame` 与 transient delta relay，但 Hearing 仍走单字段 visible delta | 应复用正式帧协议和完整帧落库，不能再建一套 Hearing token 日志或逐 delta 持久化 |
| 正式化 | `TargetHearingFormalPayloadFactory`、`MatrixKind.INTAKE`、receipt hash 与 transcript committer 全部按 V1 单消息结果工作 | V4 issue state、M2、frame manifest 必须在同一正式事务中校验与绑定 |
| 查询投影 | `GET /hearing` 把 `issue_set` 直接别名为 `question_set`，不公开 M2/issue-state authority | 前端与 UAT 无法区分 baseline issue catalog 和本轮重判后的正式状态 |
| M2 后继启动 | Intake synthesis 正式提交后立即推进 `EVIDENCE_REQUESTS_GENERATING` 并启动证据官；dossier 又硬编码 question/answer V1 | 不能在“只重构到 M2”的版本上直接启用生产 activation，否则会自动进入旧下游合同 |

### 2.3 当前候选区处理原则

当前候选区存在未提交的 `hearing_room_context.v3` 及相邻 Hearing prompt/schema 测试变更。V4 实现不得继续在该 Intake V3 结构上叠加补丁：

- 只替换庭审案情接待官两个节点的 V3 上下文与输出合同；
- 庭审证据官现有文件和行为在本轮保持不动，等待后续 M2/E1 交接设计；
- 保留与本轮无关的既有脏改动，不 reset、不覆盖；
- 新增独立 `hearing_intake_context.v4`，不把 Evidence 上下文继续塞入同一个共享 V4 assembler。

### 2.4 本次复核新增结论

本方案不是让旧后端“兼容”一组新字段，而是把 Hearing Intake 的活动路径整体切换为 V4：

- 新 activation 的 Hearing Intake 专属活动合同只接受 `hearing_question_set.v4`、`hearing_answer_bundle.v4`、两个 V4 model result、`hearing_issue_state_set.v4` 与 agent-stream.v3 frame authority；独立且形状未变的上游 `hearing-prelude-authority.v1`、M1 `case_fact_matrix.v2` 与 E1 `fact_evidence_matrix.v2` 按精确版本/hash 读取，不属于旧 Intake fallback；
- `/statements`、`hearing_party_statement.v1`、answer auto-timeout、`public_message` 单字段终态和 V1 Target operation binding 不进入新 activation；
- Java、Python、SQL check constraints、Temporal command/receipt、formal payload、transcript、projection 和 replay 必须同一发布单元切换；
- 数据库可以保留不可变的历史 V1 行，但 V4 运行路径不得 dual-read、字段回退、按旧 schema 猜测或把 V1 行当作 V4 父权威；这属于历史留存，不属于运行时兼容；
- 在 M2 下游 Evidence/Dossier 合同完成下一阶段重构前，本轮只运行仓库内隔离的 Intake stage 测试，不启用 V4 activation，也不接入会自动继续执行的正式 Hearing workflow。

## 3. 核心不变量

1. **状态选模**：模式只由正式 Hearing stage 决定，模型不得选择模式。
2. **一次调用**：QUESTION 与 SYNTHESIS 各自只调用一次模型；公开回复和结构结果来自同一 Provider 文档。
3. **M1 唯一**：每次调用只存在一个 M1 业务投影；不得再复制 `existing_fact_catalog` 等第二份事实权威。QUESTION 阶段可依据该投影形成正式争议目录，但目录必须随正式 question set 冻结，ANSWER 阶段不得重新划分一套旧争议。
4. **回答权威**：综合模式必须读取 USER、MERCHANT 各一份终态 answer bundle，包含正式 action ID、content hash、角色和回答单元。
5. **文本受信任**：模型生成的公开文字、事实摘要、立场摘要、诉求内容和争议说明不经过正则、相似度、关键词或终态 composer 改写。
6. **结构受约束**：Schema、ID、角色、source binding、顺序、唯一性、预算、矩阵父子关系、幂等、重放和状态机仍严格校验。
7. **公开优先**：`lead_public_text` 必须是 Provider 输出的第一个业务字段，并按可解码 `delta.content` 立即公开。
8. **分层流式**：只有面向用户的 `lead_public_text` 和 `frame_texts` 逐 delta 公开；manifest 与其余结构允许完整后聚合处理。
9. **单一 manifest**：一个输出只生成一个完整有序 `frame_manifest`，不为每帧重复 Header。
10. **帧级持久化**：逐 delta 不落库；完整公开字符串闭合后，和已校验 manifest 项组合成一帧一次持久化。
11. **正式状态后置**：流式帧是 attempt-scoped `OBSERVED`；完整模型结果和 M2 全部通过后才提升为正式 room message 和正式 stage output。
12. **M2 后端生成**：模型只输出绑定好的 transition/delta，不能输出完整 M2、正式事实 ID、版本、hash、parent、source refs 或 indexes。
13. **旧事实不改身份**：旧 `FACT_*` 的 category、fact target、materiality、origin 和 fact ID 不可被模型改写；命题修正使用新事实加关系。
14. **争议点优先**：模型先使用双方本轮非空回答重新绑定每个正式争议点，再生成该争议点产生的 matrix effects；fact/claim 不能成为与 issue 重判平行的第二语义源。
15. **全量本轮回答**：USER、MERCHANT 对每个正式 issue 都必须各有一个非空 current answer unit；缺少任一项时不得进入 SYNTHESIS，不得静默沿用旧立场。
16. **旧立场仅供参考**：M1 与 baseline issue 中的旧立场只用于提问、前端提示、历史对照和模型判断 `REAFFIRM/REPLACE`；当前 effective positions 必须全部来自本轮回答。
17. **每个旧 issue 都重判**：ANSWER 对每个正式旧 issue 都生成一次 `REBIND` 和新 issue version，模型依据双方本轮回答重新生成完整当前标签。
18. **新争议默认单方**：本轮回答首次提出且对方没有回应机会的争议使用新 slot，默认 `ONE_SIDED`，不能把沉默解释为同意。
19. **争议可演进**：双方可在庭审中更新自己的事实立场、诉求或直接回应；M2 必须反映当前正式状态，同时通过 parent_ref 保留 M1 历史。
20. **公开后不自动重调 Provider**：首个公开 delta 出现后，Provider 失败不能自动用第二次生成继续拼接。

## 4. 正式状态与模型调用边界

| 正式状态 | 模式 | 模型调用 | 允许结果 |
|---|---|---:|---|
| `EVIDENCE_INTRODUCTION=COMPLETED` 且当前为 `INTAKE_QUESTIONS_GENERATING` | `QUESTION_GENERATION` | 1 | 正式问题集提案与公开问题帧 |
| `PARTY_ANSWERS_OPEN` | 无 | 0 | 双方逐 issue 提交本轮非空回答；未完整覆盖时不得推进 |
| 两份完整终态 answer bundle 已封存且当前为 `INTAKE_SYNTHESIZING` | `ANSWER_SYNTHESIS` | 1 | 全量 issue 重绑定、new issues、matrix effects 与 M2 |
| 刷新、重连、成功结果重放 | `REPLAY` | 0 | 读取已存帧、正式问题集或正式 M2 |

`QUESTION_GENERATION` 与 `ANSWER_SYNTHESIS` 使用两个独立 output model 和两个独立 mode prompt；不使用一个包含大量可空字段的联合 Schema，也不允许模型从 prompt 文本推断当前模式。

## 5. 端到端链路

```text
Evidence 接待阶段正式 completion/summary authority
    -> JdbcTargetHearingPreludeAuthority 冻结并校验 M1 与已冻结 E1 的 ID/version/hash binding
    -> Hearing prelude receipt + INTAKE_QUESTIONS_GENERATING

QUESTION_GENERATION
    -> JdbcTargetHearingAgentStageInputFactory 只装载 M1 + server-owned question slots（E1 内容不入模型）
    -> target-e2e-hearing-invocation.v4 + signed AgentInvocationContext sidecar
    -> Python QUESTION V4 context/prompt/output schema
    -> Provider 一次原生结构化 SSE
    -> lead/frame public text 逐 delta；完整 frame 才写 agent_run_public_frame
    -> Python question proposal -> Java V4 formal mapper
    -> 同一事务写 formal question set + baseline issue catalog + frame bindings/messages + receipt
    -> PARTY_ANSWERS_OPEN

PARTY_ANSWERS_OPEN
    -> GET /hearing V4 projection 驱动 HearingCourtView 逐 issue 表单
    -> USER、MERCHANT 分别 POST /hearing/answers（仅 hearing_answer_bundle.v4）
    -> HearingFlowRuntimeService 校验全覆盖并各写唯一 SUBMITTED V4 action/command/receipt
    -> 第二份有效 receipt 后才允许进入 INTAKE_SYNTHESIZING

ANSWER_SYNTHESIS
    -> JdbcTargetHearingAgentStageInputFactory 装载 M1、formal question/issue catalog、两份 action ID/hash/answer units
    -> target-e2e-hearing-invocation.v4 + 同一 signed AgentInvocationContext sidecar
    -> Python ANSWER V4 context/prompt/output schema
    -> Provider 一次原生结构化 SSE
    -> lead/frame public text 逐 delta；bindings/transitions/effects 聚合到终态
    -> Python materialize canonical issue transitions -> M2 -> final issue state set
    -> Target proposal/checkpoint/terminal material
    -> Java V4 formal mapper 校验 manifest/hash/parents
    -> 同一事务写 issue-state authority、M2 stage output、frame bindings/messages 和 receipt
    -> HearingProjectionQueryService 只投影 V4 question set、answer coverage、M2/issue-state refs
    -> 本轮局部测试读取并重放 M2 后停止
```

任何箭头两端版本不一致都必须在 activation readiness 阶段失败；不得在运行时选择 V1/V4 分支。

## 6. Java 输入装载重构

### 6.1 HTTP、command 与正式 answer action

新 activation 只保留一个回答入口：

```text
POST /api/disputes/{caseId}/hearing/answers
schema_version = hearing_answer_bundle.v4
```

必须同步执行：

- 从 `HearingFlowController` 删除 `/statements`；`HearingPartyStatementRequest` 和 `HearingAnswerBundleRequest.isPartyStatement/toPartyStatement` 不进入 V4 代码路径；
- `HearingAnswerBundleRequest` 只接受 `question_set_id`、`question_set_hash` 和按正式目录有序的 `answers[]`；每项包含 `question_id/issue_id/answer_text`，附件引用若保留也必须属于当前角色和案件；
- Java 在写库前加载同一 flow/epoch 的 `hearing_question_set.v4`，校验 ID/hash、issue 顺序、角色、数量、非空与预算；
- 服务端先生成稳定 `answer_bundle_id` 和每项 `answer_unit_id`，再把这些 ID 连同 `question_set_hash/issue_state_hash` 写入规范 payload，最后计算唯一 content hash；Python 不再二次生成不同身份；
- 每个 flow/stage/participant 只能存在一条 V4 answer action；相同规范 payload 重试返回原 action，不同 payload 返回幂等冲突；稳定幂等范围由服务端 stage/participant authority 决定，不依赖前端每次点击生成的新 key；
- V4 action 对应新的正式 command discriminator `HEARING_ANSWER_BUNDLE`；Target/Temporal 不再把它映射为含义模糊的 `HEARING_STATEMENT`；
- 新 activation 要求 Target Hearing epoch 已存在；删除 V4 入口中 `targetEpoch == null` 时回落 legacy stage advance 的分支；
- 回答正文仍只生成本方私有确认消息；双方回答全部正式封存前，不公开对方逐 issue 文本。

### 6.2 QUESTION_GENERATION

Java 只从正式 prelude 和当前 stage 装载：

- `flow_schema_version`；
- `case_id`；
- `workflow_id`；
- `stage_code` 与 `stage_sequence`；
- stage/source receipt 引用；
- M1 的完整 `case_fact_matrix.v2`；
- `max_questions=5`；
- 状态机预分配的 `QUESTION_SLOT_01..05`。

每个问题 slot 在正式化时还必须形成一个同身份的 baseline issue state：模型给出 issue statement、双方历史位置和基线标签，服务端生成稳定 `issue_id/version/state_hash`，并随 `hearing_question_set.v4` 一起冻结。它用于固定“在问哪一个争议”、展示旧立场和判断本轮是 `REAFFIRM/REPLACE/WITHDRAW/NO_POSITION`，但不能替代任何一方的本轮回答，也不能直接成为 ANSWER 的 current effective position。

装载前必须校验：

- 当前 stage 精确为 `INTAKE_QUESTIONS_GENERATING`；
- M1 case ID、schema、matrix ID、version 与 self hash 有效；
- M1 与 Hearing prelude receipt、room epoch、case 和 activation 一致；
- 不装载原始 Intake 私聊、Evidence 原件或庭前 Evidence 文本。

### 6.3 ANSWER_SYNTHESIS

Java 从正式 action 表读取：

- M1；
- 唯一正式 `hearing_question_set.v4`，包括其冻结的 formal issue catalog；
- USER 与 MERCHANT 各一条终态 `ANSWER_BUNDLE` action；
- 每条 action 的 `id`、`content_hash`、payload、participant ID、participant role 和 submission status；
- 当前 stage 与 predecessor receipt。

现有 `partySubmissions` 必须改为规范化 `party_answer_bundle_catalog`，不能丢弃 action ID/hash：

```json
[
  {
    "answer_bundle_id": "HEARING_ACTION_*",
    "answer_bundle_hash": "<sha256>",
    "participant_id": "ACTOR_*",
    "participant_role": "USER",
    "submission_status": "SUBMITTED",
    "answer_units": [
      {
        "answer_unit_id": "ANSWER_UNIT_*",
        "question_id": "QUESTION_*",
        "issue_id": "ISSUE_*",
        "answer_text": "用户针对该争议点的本轮完整回答"
      }
    ],
    "source_message_ids": ["MESSAGE_*"]
  }
]
```

V4 规范化规则：

- 只接受按正式 question/issue 提交的 `hearing_answer_bundle.v4`，不再把一条自由陈述复制绑定到多个 issue；
- 每个角色对 `formal_issue_catalog` 中每个 issue 必须恰好有一个 answer unit，顺序与问题目录一致；
- `answer_text.trim()` 必须非空；这是协议完整性校验，不判断回答语义是否充分；
- 非空校验不改写正文：服务端保存并 hash 客户端提交的原始 Unicode 字符串，不做 trim、Unicode normalization 或自动标点；仅空白差异也属于不同 canonical payload，并按幂等冲突处理；
- 每个 `answer_text` 最多 2,000 字符，每个角色完整 bundle 最多 10,000 字符；超限必须在提交边界显式返回，不得在进入模型前截断；
- `answer_unit_id` 由服务端基于 bundle ID、question ID 与 issue ID 确定性生成；
- StageInput 原样保留唯一的 `submission_status=SUBMITTED`；不得再翻译为 `COMPLETED/TIMED_OUT` 或另造 `submission_source=PARTY_ACTION/AUTO_TIMEOUT`，以免 Java action、Temporal receipt 与 Python request 出现三套状态语义；
- 前端在每个争议输入区展示历史位置并提示“立场未变也请明确重述”；可提供“一键沿用此前立场”，但提交时必须展开为真实非空 `answer_text`，不能发送 `CARRY_FORWARD` 哨兵；
- 任一角色缺少 issue、出现重复 issue、空回答、未知 question/issue 或目录乱序时，不创建完整终态 bundle，不推进到 `INTAKE_SYNTHESIZING`；
- timeout/absent 不转换为旧立场，也不调用综合模型；由状态机保持等待或进入明确异常处置；
- 两个 bundle 的 participant ID 必须不同，角色集合必须精确等于 USER/MERCHANT；
- content hash 必须与正式 action payload 一致。

### 6.4 截止时间与 Temporal 推进

`PARTY_ANSWERS_OPEN` 的超时语义必须随 V4 一起重构：

- `AUTO_TIMEOUT` 不再是合法 answer action，也不计入“两方终态”；`HearingAnswerBundleV4` 的 submission status 只允许 `SUBMITTED`；
- `HearingFlowRuntimeService.expireIfDue/createTimeoutAction` 对回答阶段不得创建 `ANSWER_BUNDLE`，只能记录不可冒充回答的 deadline audit event；
- `HearingRoomWorkflowImpl.processDeadline/formalizeRequiredTimeouts` 仅可继续为 `PARTY_EVIDENCE_OPEN` 生成 evidence timeout；在回答阶段缺少任一方完整 bundle 时，必须以 `HEARING_REQUIRED_ANSWER_COVERAGE_INCOMPLETE` 失败关闭当前 stage/epoch；
- 失败的回答 stage 不推进到 `INTAKE_SYNTHESIZING`，不创建 AgentRun，不调用 Provider，不生成 M2；恢复只能通过新的正式 Hearing epoch/activation 重新开始，本计划不增加“过期后静默补交”或“自动沿用旧立场”；
- 第二份 party receipt 只有在两份 action 都是 `SUBMITTED`、两份 bundle 都覆盖同一 formal issue catalog 且 hashes 有效时，才可产生相邻 stage transition；
- V4 party receipt 必须带显式 `action_type` 与 action schema/hash；当 `action_type=HEARING_ANSWER_BUNDLE` 时状态类型只能表达 `SUBMITTED`。Evidence 阶段若仍需 timeout，使用其自身 stage-specific receipt/status 规则，不再依赖一个可把 `AUTO_TIMEOUT` 解释成 answer 的共享联合分支；
- `JdbcTargetHearingFormalizationActivities.formalizeTimeout/timeoutPayload` 必须拒绝 answer stage，任何 answer receipt 出现 timeout/absent 状态都在 Temporal signal admission 前失败；
- 查询投影与前端显示明确区分 `PENDING/SUBMITTED/ANSWER_WINDOW_EXPIRED`，不能把超时显示为已回答。

### 6.5 AgentInvocationContext sidecar

可信调用上下文不进入业务 ContextPack，但必须沿调用链传递：

```text
RoomGraphCommand.invocation_context
  -> TargetE2eHearingInvocationPublisher / canonical command material
  -> HearingTargetE2EExecutionContext
  -> GovernedTargetE2EHearingInvocationDecoder
  -> HearingGraphInvocation.agent_context
  -> GovernedHearingModelAdapter
  -> HarnessModelRunner.ainvoke_structured_stream(agent_context=...)
```

必须保持并核对 signed provider、model profile、node name、prompt profile、output schema、policy、guardrail 与 traceparent。`GovernedTargetE2EHearingInvocationDecoder` 不得通过重新构造 `HearingGraphInvocation` 丢弃 sidecar，`HearingFlowWorkflows._invocation` 与 Evidence work-item 分支也必须携带同一对象。业务 prompt 只接收允许披露的身份/角色字段；traceparent、签名、fence 和 tenant 细节保持在 transport sidecar。

## 7. `hearing_intake_context.v4` 输入上下文

### 7.1 公共设计

新建独立的 Intake assembler，不扩展 `hearing_room_context.v3`：

```text
python-agent-service/app/harness/hearing_intake_context_v4.py
```

它只服务：

- `hearing_intake_questions`；
- `hearing_intake_synthesis`。

M1 进入模型前投影为唯一 `frozen_case_matrix_projection`：

```json
{
  "projection_schema_version": "hearing_case_matrix_projection.v4",
  "source_matrix_id": "CASE_MATRIX_*",
  "source_matrix_version": 2,
  "source_matrix_hash": "<sha256>",
  "projection_hash": "<sha256>",
  "party_map": {},
  "case_overview": {},
  "claims": {},
  "fact_rows": []
}
```

模型投影保留理解案情所需的全部事实、双方正式位置、alignment、materiality、诉求和摘要；不重复 `source_refs/fact_indexes/generation_ref` 等账务字段。完整 M1 仍由后端持有并用于终态物化。

### 7.2 QUESTION_GENERATION 物理顺序

```text
1. context_header
2. mode_contract
3. authority_scope
4. frozen_case_matrix_projection
5. question_slot_catalog
6. question_policy
7. output_contract
```

`question_slot_catalog` 固定为最多五个 slot，角色由状态机确定：

```json
[
  {"question_slot_id":"QUESTION_SLOT_01","target_roles":["USER","MERCHANT"]},
  {"question_slot_id":"QUESTION_SLOT_02","target_roles":["USER","MERCHANT"]}
]
```

模型只能使用目录前缀 `QUESTION_SLOT_01..N`，不能跳号、重复或创建 slot；每个问题只能绑定 M1 中已有 `FACT_*`。

### 7.3 ANSWER_SYNTHESIS 物理顺序

```text
1. context_header
2. mode_contract
3. authority_scope
4. frozen_case_matrix_projection
5. formal_issue_catalog
6. formal_question_catalog
7. party_answer_bundle_catalog
8. transition_slot_catalog
9. matrix_transition_contract
10. output_contract
```

删除重复的 `existing_fact_catalog` 和虚构的 `answer_binding_catalog`。已有事实 ID 直接来自 M1；状态机分别预分配最多 5 个 `NEW_ISSUE_SLOT_*` 和 20 个 `NEW_FACT_SLOT_*`。新 issue slot 只允许用于本轮回答首次出现、无法归入已有正式争议点的实质争议。

`formal_issue_catalog` 与 `formal_question_catalog` 都是同一份正式 `hearing_question_set.v4` 的规范化视图，不是第二份语义权威。前者按 question slot 顺序提供：

- `issue_id/issue_version/issue_state_hash`；
- 冻结的 issue statement 与 `source_fact_ids`；
- USER、MERCHANT 的基线 effective position 及来源；
- 冻结的 `AGREED/PARTIALLY_AGREED/CONTESTED/ONE_SIDED/UNRESOLVED` 标签；
- agreed statement、conflict summary 与 `requires_resolution`。

`formal_question_catalog` 只使用同一正式 question set 中的：

- question set ID/hash；
- question ID；
- issue ID；
- fact IDs；
- shared issue text；
- USER/MERCHANT party prompts。

`party_answer_bundle_catalog` 使用上一节规范化结果，不把整条数据库 action 或内部审计字段复制给模型。

### 7.4 Python Schema、Graph 与执行器同步切换

V4 不在现有 V1 Pydantic 模型上增加 optional 字段，而是替换 Intake 两个 operation 的整套类型：

- 在 `app/schemas/hearing_flow.py` 新建严格的 V4 request/model-output/result/answer/issue-state 类型，并从 active Intake union 中移除 `HearingAnswerBundleV1/HearingPartyStatementV1`；Evidence/Judge 的旧类型不被当作 Intake fallback；
- 在 `app/agents/hearing_flow.py` 删除 Intake 对 `_intake_issue_contexts`、`_party_statement_contexts`、`_resolved_issue_mappings`、`_hearing_alignment` 和 `hearing_case_fact_matrix.delta.v1` 的调用，替换为 V4 issue-transition materializer 与 M2 materializer；
- `app/graphs/hearing/contracts.py`、`app/harness/prompt_composer.py`、`app/harness/prompt_contracts.py` 和 Target operation registry 同时切换到 V4 prompt/result/profile pins；启动时若任一 pin 仍为 V1/V3，readiness 失败；
- `app/graphs/hearing/lcel.py` 对 Intake 节点只调用 `hearing_intake_context_v4`；从 `HEARING_ROOM_CONTEXT_V3_NODES` 移除两个 Intake 节点，不提供 V3 fallback；
- `app/graph_runtime/target_e2e_room_exchange.py` 的 governed decoder 对新 activation 只接受新的 immutable invocation schema，并完整传递 `agent_context`；fixture decoder 只留在隔离测试注册，不能被生产 activation 选择；
- `HearingGraphInvocation`、graph state request hash、checkpoint version pins 和 proposal schema 必须同时升级；V1 checkpoint 不 resume、不转换、不复制到 V4。

Python 终态结果必须已经包含 canonical `frame_manifest`、完整 frames、formal issue transition set、M2、final issue state set 及各自 hashes；Java 只重新验证和授予正式权威，不重新生成业务语义。

## 8. 模型输出：公开文本流式、结构聚合

### 8.1 统一物理顺序

两个模式都遵循：

```text
lead_public_text
schema_version
frame_manifest
frame_texts
后续 mode-specific structured fields
```

- `lead_public_text` 是状态机拥有身份的第 1 帧，模型只生成文字；
- `frame_manifest` 是一次性、完整、有序的小型机器清单；
- manifest 从 `frame_sequence=2` 开始；
- `frame_texts[i]` 精确对应 `frame_manifest[i]`；
- 每个公开字符串按 Provider 实际可解码前缀输出；
- 其他结构字段不进入公开 delta，可等待完整值或终态。

### 8.2 QUESTION_GENERATION 输出

```json
{
  "lead_public_text": "庭前案情已经装载，我将围绕核心争议请双方进一步说明。",
  "schema_version": "hearing_intake_question_stream.v4",
  "frame_manifest": [
    {
      "frame_sequence": 2,
      "frame_type": "SHARED_ISSUE_QUESTION",
      "question_slot_id": "QUESTION_SLOT_01",
      "fact_ids": ["FACT_01"]
    }
  ],
  "frame_texts": [
    "关于商品实际状态，请双方分别说明各自观察到的情况。"
  ],
  "question_bindings": [
    {
      "question_slot_id": "QUESTION_SLOT_01",
      "issue_baseline": {
        "issue_statement": "商品实际表现是否符合双方约定",
        "source_fact_ids": ["FACT_01"],
        "effective_party_positions": {
          "USER": {
            "position_source": "M1",
            "position_summary": "用户认为商品实际表现未达到约定。"
          },
          "MERCHANT": {
            "position_source": "M1",
            "position_summary": "商家认为商品交付状态符合约定。"
          }
        },
        "alignment": {
          "status": "CONTESTED",
          "agreed_statement": null,
          "conflict_summary": "双方对商品实际表现是否符合约定存在分歧。"
        }
      },
      "party_prompts": {
        "USER": "请说明你收到商品后的实际表现及发现过程。",
        "MERCHANT": "请说明发货商品的状态及判断依据。"
      }
    }
  ]
}
```

正式化规则：

- `frame_texts[i]` 成为该问题唯一公开 `question_text`；`issue_baseline.issue_statement` 是同一 slot 的结构化争议表述，不额外作为聊天文本发布；
- `target_roles` 从 slot 注入，不由模型重复输出；
- `question_id/issue_id/issue_version/issue_state_hash` 由服务端根据正式 question set authority、slot 与规范化 baseline 确定性生成；
- 后端只校验 baseline 中的 fact ID、角色、枚举、空值结构、顺序和预算，不以正则或机械 stance 逻辑重判其自然语言和 alignment；
- `question_bindings` 与 manifest 必须一一对应，但允许聚合到终态后再解析；
- Java 不再把公开消息与问题文本二次拼接。

### 8.3 ANSWER_SYNTHESIS 输出

```json
{
  "lead_public_text": "双方陈述已经收齐，我将按争议点汇总一致内容和仍待处理的分歧。",
  "schema_version": "hearing_intake_answer_stream.v4",
  "frame_manifest": [
    {
      "frame_sequence": 2,
      "frame_type": "REBIND_ISSUE_SYNTHESIS",
      "issue_ref": "ISSUE_01"
    }
  ],
  "frame_texts": [
    "双方均确认商品已经交付，但对商品实际表现是否符合约定仍存在分歧。"
  ],
  "issue_rebindings": [
    {
      "issue_id": "ISSUE_01",
      "party_bindings": {
        "USER": {
          "binding_action": "REAFFIRM",
          "answer_bundle_id": "HEARING_ACTION_USER",
          "answer_unit_id": "ANSWER_UNIT_USER_01",
          "current_position": {
            "position_summary": "用户仍认为商品实际表现未达到约定。"
          }
        },
        "MERCHANT": {
          "binding_action": "REPLACE",
          "answer_bundle_id": "HEARING_ACTION_MERCHANT",
          "answer_unit_id": "ANSWER_UNIT_MERCHANT_01",
          "current_position": {
            "position_summary": "商家本轮确认商品部分性能未达到约定。"
          }
        }
      },
      "effective_party_positions": {
        "USER": {
          "position_source": "CURRENT_ANSWER",
          "position_summary": "用户仍认为商品实际表现未达到约定。"
        },
        "MERCHANT": {
          "position_source": "CURRENT_ANSWER",
          "position_summary": "商家本轮确认商品部分性能未达到约定。"
        }
      },
      "current_alignment": {
        "status": "PARTIALLY_AGREED",
        "agreed_statement": "双方均确认商品至少部分性能未达到约定。",
        "conflict_summary": "双方对未达约定的范围仍有分歧。"
      }
    }
  ],
  "new_issue_proposals": [],
  "matrix_effects": {
    "claim_effects": [],
    "existing_fact_effects": [],
    "new_fact_effects": [],
    "relationship_effects": []
  },
  "matrix_summary": {
    "summary_text": "商品已交付，但双方对实际表现是否符合约定仍有分歧。",
    "summary_fact_refs": ["FACT_01"]
  }
}
```

`issue_rebindings` 必须按 `formal_issue_catalog` 顺序覆盖每个正式旧 issue 恰好一次；两方 binding 都必须引用该角色针对该 issue 的本轮非空 answer unit。`new_issue_proposals` 按 `NEW_ISSUE_SLOT_*` 顺序只描述本轮回答新出现的实质争议。公开 manifest 必须依次包含全部正式旧 issue，再包含全部新 issue，因此 ANSWER 至少有一个 issue frame，不再存在空 manifest 或旧 issue 静默跳过路径。

公开 frame 文本受信任，不与结构字段做关键词或字节级语义比对。claim、fact、relationship 变化只能出现在 `matrix_effects`，并且必须引用一个本轮已重绑定的旧 issue 或新 issue，不能成为平行的第二套业务判断。

## 9. 全量本轮回答与争议重绑定机制

庭审综合的首要对象是正式争议点，而不是事实 patch。QUESTION 阶段已经冻结 1–5 个 formal issues；PARTY_ANSWERS_OPEN 阶段要求双方逐 issue 给出本轮非空回答；ANSWER 模型随后对每个旧 issue 都重新绑定并重判，不存在 `PRESERVE` 或 `CARRY_FORWARD` 分支。

### 9.1 回答覆盖是进入综合阶段的硬前置

对 formal issue catalog 中每个 issue：

- USER 必须恰好提交一个与该 question/issue 绑定的非空 answer unit；
- MERCHANT 必须恰好提交一个与该 question/issue 绑定的非空 answer unit；
- answer unit 必须来自当前 stage、当前角色、当前正式 bundle；
- 任一项缺失、空白、重复、乱序或绑定到错误 issue，Java 不封存完整 bundle，状态机不进入 `INTAKE_SYNTHESIZING`，Provider 调用次数保持 0；
- “立场未变”也必须在当前 answer 中明确重述；前端可展示旧立场并一键填入，但最终提交的是当前非空文本，而不是服务端 carry 指令；
- timeout/absent 不会把旧立场自动提升为本轮立场，也不会生成 M2。

这使每个 current position 都有本轮明确来源，同时避免后端猜测“没有提到”究竟是维持、遗漏还是放弃。

### 9.2 每方绑定动作

每个旧 issue 对 USER、MERCHANT 分别给出一个本轮绑定动作：

- `REAFFIRM`：当前回答明确重述此前立场；必须给出完整 current position 和当前 answer source；
- `REPLACE`：当前回答改变、纠正或实质补充此前立场；必须给出完整 current position 和当前 answer source；
- `WITHDRAW`：当前回答明确撤回此前立场；current position 为空，但撤回来源必须保留；
- `NO_POSITION`：当前回答明确表示本轮无法形成或不主张具体立场；current position 为空，但该明确回答仍必须绑定。

模型负责判断动作、生成当前位置和当前标签。后端只校验 answer units 是否真实存在、角色/issue 是否一致以及动作对应的空值结构；不比较新旧文字，也不重判 `REAFFIRM/REPLACE`。

### 9.3 每个旧 issue 必须重新绑定

每个旧 issue 都必须生成一项 rebind，且两方都只能使用本轮回答：

```json
{
  "issue_id": "ISSUE_01",
  "party_bindings": {
    "USER": {
      "binding_action": "REPLACE",
      "answer_bundle_id": "HEARING_ACTION_USER",
      "answer_unit_id": "ANSWER_UNIT_USER_01",
      "current_position": {
        "position_summary": "用户现接受部分退款方案。"
      }
    },
    "MERCHANT": {
      "binding_action": "REAFFIRM",
      "answer_bundle_id": "HEARING_ACTION_MERCHANT",
      "answer_unit_id": "ANSWER_UNIT_MERCHANT_01",
      "current_position": {
        "position_summary": "商家本轮仍接受部分退款方案。"
      }
    }
  },
  "effective_party_positions": {
    "USER": {
      "position_source": "CURRENT_ANSWER",
      "position_summary": "用户现接受部分退款方案。"
    },
    "MERCHANT": {
      "position_source": "CURRENT_ANSWER",
      "position_summary": "商家本轮仍接受部分退款方案。"
    }
  },
  "current_alignment": {
    "status": "AGREED",
    "agreed_statement": "双方现均接受部分退款方案。",
    "conflict_summary": null
  }
}
```

重判规则：

- 两方 binding 都必须引用本轮各自的非空 answer unit；
- `REAFFIRM/REPLACE` 使用模型给出的完整当前位置和当前 answer sources；
- `WITHDRAW/NO_POSITION` 没有 current position，但仍保留本轮明确回答来源；
- 模型基于两方 effective positions 重新生成 `AGREED/PARTIALLY_AGREED/CONTESTED/ONE_SIDED/UNRESOLVED`；
- 只有一方本轮形成有效 position、另一方本轮明确 `WITHDRAW/NO_POSITION` 时，才可形成 `ONE_SIDED`；
- baseline 只用于模型识别是 `REAFFIRM/REPLACE` 和记录历史差异，不能填充 current position；
- 每个旧 issue 都产生新 version/state hash，即使双方都 `REAFFIRM`。

结构约束：

- 每个角色恰好一个动作，且所有动作都必须绑定当前角色的非空 answer unit；
- `REAFFIRM/REPLACE` 的 current position 必须非空；`WITHDRAW/NO_POSITION` 的 current position 必须为空；
- effective positions 只能由两方当前 binding 生成，`position_source` 固定为 `CURRENT_ANSWER`；
- 每个旧 issue 必须有 current alignment 和一个公开综合 frame；
- alignment 的自然语言与标签受信任，后端只校验枚举和空值结构；
- `requires_resolution` 由 status 是否为 `AGREED` 确定性生成。

### 9.4 本轮新争议点

回答中首次出现且无法归入已有正式 issue 的实质争议使用 `NEW_ISSUE_SLOT_*`。如果只有一方提出，而对方没有针对该新问题的回应机会，默认保存为单方争议：

```json
{
  "new_issue_slot_id": "NEW_ISSUE_SLOT_01",
  "issue_kind": "NEW_UNILATERAL_ISSUE",
  "issue_statement": "商品持续运行后是否出现异常降速",
  "raised_by_role": "USER",
  "source_answer_bundle_id": "HEARING_ACTION_USER",
  "source_answer_unit_ids": ["ANSWER_UNIT_USER_01"],
  "fact_refs": ["NEW_FACT_SLOT_01"],
  "effective_party_positions": {
    "USER": {
      "position_source": "CURRENT_ANSWER",
      "position_summary": "用户陈述商品持续运行后出现异常降速。"
    },
    "MERCHANT": null
  },
  "current_alignment": {
    "status": "ONE_SIDED",
    "agreed_statement": null,
    "conflict_summary": "该事项由用户在本轮首次提出，商家尚无回应机会。"
  },
  "counterparty_response_opportunity": "NOT_PROVIDED",
  "requires_resolution": true
}
```

新争议规则：

- 不能把对方未回应解释为认可、否认或部分认可；
- 不能从对方旧的相邻争议位置猜测其对新 issue 的态度；
- 默认 `ONE_SIDED + NOT_PROVIDED + requires_resolution=true`；
- 如果两份本轮 answer units 都独立、明确地绑定同一个新 issue，可使用 `NEW_SHARED_ISSUE + INDEPENDENTLY_EXERCISED` 并由模型判断当前标签，但仍记录 `additional_response_round=NOT_OPENED`；
- 新 issue 正式 ID 由服务端根据 case ID、Hearing epoch、stage sequence 和 slot 确定性生成；transition hash 随后覆盖这些正式 ID，避免 ID 与 hash 循环依赖；
- 新 issue 与旧 issue 一起进入当前争议状态集，但不会触发第二次双方回答。

### 9.5 当前争议状态集

终态应物化一份 `hearing_issue_state_set.v4`，而不是只把 issue mappings 留在临时模型对象中。它包含：

- 所有旧 issue 的本轮重绑定状态与新 version；
- 本轮新 issue 的正式 ID、单方/共享类型与回应机会；
- 双方 effective positions 及其来源；
- 当前 alignment；
- source question set、answer bundle hashes；
- 产生 M2 的 transition set hash；
- 最终 M2 ID/version/hash binding。

分析顺序因此固定为：

```text
M1 + 正式 issue set + 双方 answers
  -> 每个旧 issue 使用两方 current answers 全量 REBIND
  -> 新 issue proposals
  -> canonical issue transition set + transition hash
  -> matrix effects
  -> M2
  -> hearing_issue_state_set.v4 绑定 M2 ID/version/hash
```

### 9.6 争议重判产生的 matrix effects

claim/fact/relationship 不再作为与 issue 重判并列的自由输出；每一项必须带 `source_issue_refs`，且至少引用一个本轮已重绑定旧 issue 或新 issue。

#### Claim effects

```json
{
  "source_issue_refs": ["ISSUE_01"],
  "effect_type": "INITIATOR_CLAIM_REPLACE",
  "subject_role": "USER",
  "answer_bundle_id": "HEARING_ACTION_USER",
  "answer_unit_ids": ["ANSWER_UNIT_USER_01"],
  "replacement": {
    "requested_resolution": "PARTIAL_REFUND",
    "requested_amount": 200.0,
    "requested_items": null,
    "reason_summary": "用户将诉求调整为部分退款。",
    "position_summary": "用户现请求部分退款 200 元。"
  }
}
```

发起方 effect 只能绑定发起方 answer；respondent effect 只能绑定 respondent answer。`respondent_reported_by_initiator` 保留为历史单方转述，respondent 新直接状态由后端注入 `RESPONDENT_DIRECT_HEARING`。

#### Existing fact effects

```json
{
  "source_issue_refs": ["ISSUE_01"],
  "fact_id": "FACT_01",
  "party_updates": {
    "USER": {
      "answer_bundle_id": "HEARING_ACTION_USER",
      "answer_unit_ids": ["ANSWER_UNIT_USER_01"],
      "stance": "CONFIRM",
      "position_summary": "用户确认商品持续运行后出现明显降速。",
      "asserted_value": "持续运行后降速"
    },
    "MERCHANT": null
  },
  "alignment": {
    "status": "ONE_SIDED",
    "agreed_statement": null,
    "conflict_summary": "当前只有用户对该事实形成直接陈述。"
  }
}
```

旧 fact 的 identity/materiality/origin 不输出、不修改；未被 effect 触及的事实完整保留。

#### New fact and relationship effects

```json
{
  "new_fact_slot_id": "NEW_FACT_SLOT_01",
  "source_issue_refs": ["NEW_ISSUE_SLOT_01"],
  "category": "PRODUCT_STATE",
  "fact_target": "商品持续运行后是否出现异常降速",
  "materiality": "SUPPORTING",
  "party_positions": {},
  "alignment": {}
}
```

后端根据 case、M1、stage 和 slot 生成稳定 `FACT_HEARING_*`。如果本轮是在纠正或限定旧命题，模型还必须输出关系：

```json
{
  "relationship_type": "QUALIFIES",
  "from_fact_ref": "NEW_FACT_SLOT_01",
  "to_fact_id": "FACT_01",
  "source_issue_refs": ["NEW_ISSUE_SLOT_01"]
}
```

关系只允许 `CORRECTS/QUALIFIES/DUPLICATES`；不能直接改写旧事实命题。

## 10. M2 确定性物化算法

Python 应建立独立、纯函数式的 `materialize_hearing_case_matrix_v4`：

1. 校验 M1 schema、case、matrix ID/version/self hash；
2. 校验正式问题集、两份 answer bundle ID/hash/role 与当前 stage；
3. 校验输出根字段顺序、manifest、frame texts 与 mode-specific 结构；
4. 校验两份 bundle 对每个正式旧 issue 都恰好有一个非空 current answer unit；
5. 对每个正式旧 issue 要求恰好一个 rebind，并解析两方 current party bindings、effective positions 与新 alignment；
6. 解析 `NEW_ISSUE_SLOT_*`，生成稳定新 issue ID，并按回应机会规则建立单方/共享状态；
7. 生成不含 M2 result hash 的 canonical issue transition set 及 transition hash；
8. 校验所有 matrix effects 的 `source_issue_refs` 只引用本轮 rebind/new issue；
9. 深拷贝 M1，建立旧事实 ID/顺序和新 fact slot 映射；
10. 应用 issue-bound initiator/respondent claim effects，保留历史 reported position；
11. 对既有 fact 只更新 effect 明确提供的角色 position，追加对应 answer sources；
12. 对每个新 fact slot 创建稳定 `FACT_HEARING_*`、Hearing origin、初始 truth/coverage 状态；
13. 应用模型给出的 fact alignment 与 relationships；
14. 未触碰的旧 fact、position、alignment、relationship 和 evidence coverage 原样保留；
15. 解析 `matrix_summary.summary_fact_refs`，把 new fact slots 转换为正式 fact IDs；
16. 设置新 case overview、claim conflict、fact indexes 和 dispute points；
17. 设置 `matrix_kind=HEARING_CLARIFIED_FROZEN`；
18. 设置 `matrix_version=M1+1` 与精确 `parent_ref=M1(id/version/hash)`；
19. source refs 合并 M1 与正式 question/bundle/message sources，按首次出现顺序去重；
20. `source_context_hash` 覆盖 M1 hash、正式 question set hash、两份 answer bundle hash 与 issue transition hash；
21. 生成稳定 matrix ID 和最终 content hash，并再次通过 `CaseFactMatrixV2` self-hash 校验；
22. 最后物化 `hearing_issue_state_set.v4`，绑定 transition hash 与 M2 ID/version/hash；
23. 在同一正式结果中返回 issue state set、M2 与公开 frame manifest。

相同 M1、问题集、answer bundles 和模型终态必须得到字节完全相同的 issue state set 与 M2。旧 baseline state 保留在历史链中，但不得被复制为 current position。模型不能提供或覆盖正式 issue ID/version/hash，亦不能提供或覆盖 M2 的 matrix ID/version/hash/parent/source refs/indexes。

## 11. 后端校验边界

### 11.1 协议与正式权威校验

后端必须校验：

- 当前正式 stage 与 mode 精确匹配；
- `AgentInvocationContext` 的 provider、model、node、prompt、output schema、policy、trace、command、epoch 与 fence binding；
- 输出根字段名称和物理顺序精确符合当前 mode 的 V4 Schema；
- M1 的 case、matrix ID、version、self hash，以及 question set 对 M1 的 binding；
- QUESTION 的 slot 连续、唯一且为 1–5 个，每个 fact ref 都存在于 M1；
- QUESTION 每个 slot 恰好形成一份 baseline issue，USER/MERCHANT 角色齐全，source refs 只来自允许的 M1 fact/claim；
- 正式 question set 中 question、baseline issue、question slot 的顺序与身份一一对应；
- ANSWER 中两份 bundle 的 ID、hash、participant、角色、终态状态和 answer unit 归属；
- `frame_manifest.length == frame_texts.length`，frame sequence 连续，frame type、slot/issue ref 和数量合法；
- 所有字符、数组、文档、上下文预算，以及 frame hash、terminal manifest hash、issue transition hash、issue state hash 和 M2 self hash；
- command/attempt/frame 幂等、checkpoint、正式事务、重放与状态机前驱。

### 11.2 全量回答与旧争议重绑定校验

- 两份 bundle 都必须按 formal issue catalog 顺序覆盖每个 question/issue 恰好一次；
- 每个 answer unit 的 `answer_text.trim()` 必须非空，且 question ID、issue ID、bundle、participant role 必须一致；
- 缺失、重复、乱序、空回答、timeout/absent 均不得进入 SYNTHESIS，Provider 调用次数必须为 0；
- `issue_rebindings` 必须按 formal issue catalog 顺序覆盖每个旧 issue 恰好一次，不得缺失、重复、改序或创建旧 issue ID；
- 每个旧 issue 的 USER、MERCHANT 都必须恰好使用一个 `REAFFIRM/REPLACE/WITHDRAW/NO_POSITION`；
- 每个 binding 都必须通过单数 `answer_unit_id` 引用该角色对该 issue 的唯一当前非空 answer unit；不存在一项 rebind 多绑/漏绑、无来源的当前 position，也不存在服务端沿用旧 position 的分支；
- `REAFFIRM/REPLACE` 必须给出非空 current position；`WITHDRAW/NO_POSITION` 必须令 current position 为空，但仍保留当前 answer source；
- effective positions 只能从当前 binding 生成，来源固定为 `CURRENT_ANSWER`；
- 每个旧 issue 都必须生成 current alignment、新 issue version/state hash 和一个公开综合 frame；
- baseline position 只能用于历史对照和动作判断，不能进入 current effective position。

### 11.3 新争议与 matrix effect 校验

- `new_issue_proposals` 只能按序使用输入中的 `NEW_ISSUE_SLOT_01..05`，不得跳号、重复或冒充旧 issue；
- `NEW_UNILATERAL_ISSUE` 只能绑定提出方的当前 answer units，另一方不得有推测位置；其状态必须为 `ONE_SIDED`、`counterparty_response_opportunity=NOT_PROVIDED`、`requires_resolution=true`；
- `NEW_SHARED_ISSUE` 必须分别绑定 USER 与 MERCHANT 的当前 answer units，`counterparty_response_opportunity=INDEPENDENTLY_EXERCISED`，并记录 `additional_response_round=NOT_OPENED`；alignment 由模型给出；
- 新 issue 不触发第二轮回答，也不能把沉默解释为认可、否认或部分认可；
- ANSWER manifest 必须严格等于“按正式目录顺序的全部旧 issue + 按 slot 顺序的全部新 issue”，因此数量为 1–10；
- 所有 claim、existing fact、new fact、relationship effect 的 `source_issue_refs` 必须至少引用一个本轮重绑定旧 issue 或新 issue；
- 没有 source issue、跨角色使用 answer source、修改旧 fact 身份字段均失败关闭；
- new fact/relationship/summary refs 必须能在 M1 或预分配 slot 中解析，且生成顺序稳定；
- 不含 M2 binding 的 canonical issue transition set 必须先完成并通过 transition hash 校验，随后才允许应用 matrix effects；M2 生成后再封装最终 `hearing_issue_state_set.v4`，避免自引用。

### 11.4 明确不做语义后验

- 不用正则判断“同意、拒绝、退款、责任”等语义；
- 不用文本重合度核对 position、alignment、issue statement 或公开回复；
- 不再使用 stance/asserted value 的机械规则二次计算模型标签；
- 不比较 baseline 与 current answer 来复核模型给出的 `REAFFIRM/REPLACE`；
- 不把模型诉求、事实、争议摘要或公开回复替换成固定话术；
- 不使用第二次模型调用验证第一次模型；
- 不在终态重新 compose 一套 public reply；
- 不要求公开文字与结构摘要字节相同，也不因自然语言风格差异拒绝结构权威合法的输出。

这些限制不削弱协议安全：Schema、正式 ID、角色、回答来源、覆盖顺序、预算、幂等、重放、状态机和 hash 仍全部严格校验。

## 12. 分层流式输出

### 12.1 流式层级

| 输出区域 | 发布时机 | 是否逐 delta | 持久化时机 |
|---|---|---:|---|
| `lead_public_text` | Provider 可解码字符串前缀出现 | 是 | 字符串闭合后存一帧 |
| `frame_manifest` | 整个数组闭合并完成结构校验 | 否，内部结构 | 随 frame authority 引用 |
| `frame_texts[i]` | manifest 已通过，当前字符串前缀出现 | 是 | 当前字符串闭合后存一帧 |
| question binding 与 baseline issue | 完整值或终态 | 否 | 随正式 question set |
| issue rebindings/new issues | 完整值或终态 | 否 | 随 issue state set |
| matrix effects/summary | 终态 | 否 | 随 M2 |

QUESTION manifest 固定为 1–5 个问题帧。ANSWER manifest 固定包含全部 1–5 个旧 issue，再追加 0–5 个新 issue，因此为 1–10 帧；旧 issue 不得省略，也不存在空 manifest。

### 12.2 增量投影器

扩展现有通用、显式 opt-in 的增量 projector；不新建 Hearing 私有 SSE 协议：

- `lead_public_text` 使用 root `string_prefix`；
- `frame_manifest` 使用完整 JSON value gate，只作为 executor 内部 authority，不直接公开；
- `frame_texts` 使用按数组项索引的 `json_array_string_prefixes`；
- manifest 未完整或未通过 ID/顺序/预算校验前，不开放 `frame_texts`；
- ANSWER gate 同时建立“frame index → formal issue 或 new issue slot”的稳定映射；
- 每个字符串正确处理 JSON escapes、Unicode 和累计前缀；
- 不等待标点、完整语义、数组 `]` 或模型终态；
- bindings、issue transitions、matrix effects 与 summary 不登记为 visible fields。

现有 projector 只支持 root string gate，V4 还必须增加“已完成 hidden JSON value → 后续 array string prefixes”的通用依赖；不得用 Hearing case-specific 字符串扫描代替。模型仍只输出一个顶层 manifest，不输出逐帧 Header：

- lead 的 frame-1 header 由 executor 根据 command/stage 固定生成，在首个 lead delta 前发布；
- manifest 闭合后，executor 从每个 manifest item 生成 agent-stream.v3 `public_frame_start`，不是把 header 再塞回模型输出；
- 每个 `frame_texts[i]` delta 转成同一 frame 的 `public_text_delta`；字符串闭合后生成完整 snapshot/hash/commit；
- terminal 必须证明逐帧 delta 拼接文本、manifest item、frame hash 与 result 中完整文本逐字一致。

### 12.3 关闭公开文本聚合等待

庭审接待官 V4 的公开字段必须绕过通用 `V2DeltaCoalescer` 和 legacy `visible_delta` 落库路径：

- `lead_public_text` 与 `frame_texts` 不使用 75ms 时间窗；
- 不按句号、完整句、最小字符数或语义帧等待；
- 一个 Provider 可解码增量产生一个应用层公开增量；
- 仅允许网络栈自然合包，不允许应用层主动延迟；
- 大型单次 Provider delta 仍可按传输上限切块，但必须立即连续发送。

结构字段允许聚合，因为它们不作为打字效果展示，也不影响首包。

### 12.4 稳定 frame 身份与前端提升

服务端生成：

```text
frame_id = stable(command_id, attempt_id, stage_sequence, frame_sequence)
```

Agent Stream 复用仓库现有 `agent-stream.v3` frame 事件，传输 Provider 的原始公开文字：

```text
public_frame_start(frame_id, frame_sequence, frame_type, public_header)
public_text_delta(frame_id, delta_index, delta)
active_frame_snapshot(frame_id, complete_text)
public_frame_committed(frame_id, hashes, durable_cursor)
```

Java 侧复用 `AgentRunTransientStreamPublisher`：start/delta/snapshot 在连接期直接转发，不推进 durable cursor；完整 frame commit 才通过 `PostgresAgentRunV2EventStore` 写入 `agent_run_public_frame`。前端按 frame ID 创建临时消息并追加 delta；正式 room message 使用同一 publication key，终态刷新时把临时帧提升为正式帧，不新增第二条重复消息。

`AgentStreamOperationRegistry`、NDJSON parser、Target Hearing executor 和前端 SSE store 必须同时移除 `public_message` 白名单假设并绑定 V3 frame events。不得保留“V1 visible_delta 或 V3 frame 二选一”的运行分支。

## 13. 帧持久化、失败、重试与重放

### 13.1 Attempt 生命周期

- Provider 调用前持久化 attempt/run 权威；
- 接受并发布首个公开 delta 前写一次 `public_output_started`；
- start/delta/snapshot 通过 transient relay 发送，逐 delta 不写数据库、不推进 durable cursor；
- lead 或 frame text closing quote 到达后，按 agent-stream.v3 的 start + complete snapshot + commit 原子批次保存完整 text、UTF-8 length、manifest-derived public header 与 frame hash；
- `agent_run_public_frame.commit_status=COMMITTED` 只表示该 attempt 的完整帧可重放，不表示它已经成为正式庭审消息；
- QUESTION 完整输出通过后，问题帧、question set 与 baseline issue catalog 在同一正式事务中提交；
- ANSWER 完整输出、issue transition set、issue state set 与 M2 全部通过后，综合帧、frame authority、frame-to-room-message binding、stage output 与 domain receipt 在同一正式事务中提交；
- 为 Hearing 新增的正式 frame binding 必须外键绑定 `agent_run_public_frame(agent_run_id, attempt_id, frame_id)`、正式 receipt 和 room message，防止 Java 使用另一段终态文字造消息。

### 13.2 失败规则

- 回答覆盖未完成：不创建 Provider attempt，继续停留在回答阶段；
- 首个公开 delta 前 Provider 失败：允许按模型调用策略重试；
- 首个公开 delta 后 Provider/Schema/结构校验失败：禁止自动重调 Provider，本 attempt 标记失败；
- 已保存但未正式绑定的 attempt frame 保留审计，不进入正式 transcript；
- 新 attempt 使用新的 frame namespace，不与失败 attempt 拼接；
- 模型完整结束、仅 proposal/M2/Java 事务失败：从已保存 terminal material 重试正式化，不重跑 Provider；
- manifest 或结构终态失败时，已展示的 provisional 文本由前端标记该 attempt 失败，刷新后不得冒充正式消息。

### 13.3 重放规则

- 成功 command 重放读取相同 terminal material、frame manifest/text/hash、formal issue catalog、issue state set 与 M2，不调用 Provider；
- 同 attempt/frame ID 写入相同字节返回相同 receipt，不同字节冲突失败关闭；
- 重放保证完整帧字节和顺序，不要求复刻 Provider 原始 delta 切片或时间间隔；
- ambiguously accepted formal commit 只重试外层事务，不重新执行模型、争议重判或 M2 生成。

## 14. 预算

### 14.1 输入预算

- Harness 模型输入窗口沿用 32,000 token profile；
- V4 业务 ContextPack 预算固定为 20,000 个保守估算 token；
- 为系统提示、模式 prompt、响应 Schema、包装和估算误差预留至少 12,000 token；
- 构建时同时记录统一估算值与目标 Provider tokenizer 实测值；
- `context_header/mode_contract/authority_scope/frozen_case_matrix_projection/output_contract` 为必需段；
- QUESTION 的 slot/policy 与 SYNTHESIS 的 formal issue/question/two-party answer/transition 目录为必需段；
- 每方每 issue 的本轮 `answer_text` 上限 2,000 字符，每方 bundle 上限 10,000 字符，两方回答合计上限 20,000 字符；
- 任一必需段放不下时返回 `HEARING_INTAKE_CONTEXT_BUDGET_EXCEEDED`，禁止静默删事实、删旧 issue、删任一当前回答或改用摘要猜测；
- M1 最多 200 facts；超过上下文预算的超大型案件留待后续显式分批方案，不在本轮隐式截断。

### 14.2 QUESTION 输出预算

| 项 | 上限 |
|---|---:|
| `lead_public_text` | 600 字符 |
| 问题/baseline issue | 1–5 |
| 每帧公开问题 | 1,200 字符 |
| 每方 party prompt | 1,200 字符 |
| 每问题 fact refs | 20 |
| 每方 baseline position | 2,000 字符 |
| 总公开文本 | 6,600 字符 |
| 完整模型文档 | 192 KiB |

### 14.3 ANSWER 输出预算

| 项 | 上限 |
|---|---:|
| `lead_public_text` | 800 字符 |
| 正式旧 issue rebindings | 1–5，必须全部覆盖 |
| new issue slots | 0–5 |
| 公开综合帧 | 1–10，全部旧 issue + new issue |
| 每帧综合文字 | 2,000 字符 |
| 每方每 issue position summary | 2,000 字符 |
| 每方每 issue `answer_unit_id` | 恰好 1，单数字段 |
| claim effects | 0–2，每角色最多一项 |
| existing fact effects | 0–200 |
| new fact slots | 0–20 |
| relationship effects | 0–40 |
| summary fact refs | 0–200 |
| 总公开文本 | 20,800 字符 |
| 完整模型文档 | 512 KiB |

所有数组、字符串与文档预算在 Provider Schema、增量 projector、Pydantic 模型、Target payload store 和 Java loader 中保持一致；任一层声明不同上限时 readiness 失败。

## 15. Prompt 重构

Prompt 拆分为：

```text
dispute_intake_officer/hearing_intake_officer_v4.md
dispute_intake_officer/hearing_intake_question_generation_v4.md
dispute_intake_officer/hearing_intake_answer_synthesis_v4.md
```

公共 prompt 只说明角色、职责边界、共享庭审可见性、禁止证据真伪判断/责任裁决和单一 ContextPack 规则。QUESTION prompt 负责从 M1 形成问题及 baseline issue 提案；ANSWER prompt 负责在冻结的 formal issue catalog 上使用双方全量 current answers 重新绑定每个旧 issue、识别新争议并生成 issue-bound matrix effects。两者都完整声明输入物理顺序、输出物理顺序和公开字段优先规则。

必须提供结构完整、ID 可由输入解析的 input-to-output few-shot：

- 一个问题绑定一个事实并形成双方 baseline positions；
- 一个问题绑定多个事实并形成单一 baseline issue；
- 双方都明确重述旧立场：两方 `REAFFIRM`，仍全量生成新 issue state；
- USER `REAFFIRM`、MERCHANT `REPLACE`：只使用两方当前回答重新判定标签；
- 一方 `REPLACE` 后把 `CONTESTED` 重判为 `AGREED`；
- 一方 `WITHDRAW`，另一方有当前 position，生成结构合法的当前标签；
- 一方明确回答“本轮不主张具体立场”时使用有 current source 的 `NO_POSITION`；
- 缺少任一 issue 回答时是 Provider 前协议失败，不提供沿用旧值的模型示例；
- 一方首次提出新争议：`NEW_UNILATERAL_ISSUE + ONE_SIDED + NOT_PROVIDED`；
- 双方回答独立明确涉及同一新争议：`NEW_SHARED_ISSUE + INDEPENDENTLY_EXERCISED + NOT_OPENED`；
- 发起方修改处理诉求、被发起方修改直接回应或替代方案，effect 只能引用对应 current issue；
- 新 fact slot 使用 `QUALIFIES/CORRECTS` 关联旧事实；
- 两方均 `REAFFIRM` 且矩阵语义没有变化时允许 `matrix_effects` 为空，但 issue rebind/frame 仍必须存在；
- 所有公开文本在前，bindings、issue transitions、matrix effects 和 summary 在后。

Few-shot 不得出现无法由输入目录解析的 `ANSWER_USER`、虚构 fact/issue ID，亦不得用关键词规则暗示后端二次判定语义。

## 16. 跨语言合同与版本

本轮冻结：

| 合同 | 版本 |
|---|---|
| 模型业务上下文 | `hearing_intake_context.v4` |
| question model output | `hearing_intake_question_stream.v4` |
| synthesis model output | `hearing_intake_answer_stream.v4` |
| Python question result | `hearing_intake_questions.v4` |
| Python synthesis result | `hearing_intake_synthesis.v4` |
| Java formal question set + baseline issue catalog | `hearing_question_set.v4` |
| 双方逐 issue 回答 bundle | `hearing_answer_bundle.v4` |
| Java/Temporal party command | `HEARING_ANSWER_BUNDLE` |
| Target immutable invocation | `target-e2e-hearing-invocation.v4` |
| Hearing 前置冻结权威（保持现有独立合同） | `hearing-prelude-authority.v1`，内部精确绑定 M1 `case_fact_matrix.v2` 与 E1 `fact_evidence_matrix.v2` |
| 当前正式争议状态集 | `hearing_issue_state_set.v4` |
| 最终案情矩阵 | `case_fact_matrix.v2`，生成 `HEARING_CLARIFIED_FROZEN` 后继版本 |
| 流式传输 | `agent-stream.v3` frame events |
| 公开 frame authority | `hearing-public-frame.v4` |
| frame-to-message binding | `hearing-public-frame-binding.v4` |
| GET Hearing projection | `hearing-flow-projection.v4` |
| Hearing Intake prompt profile | `HEARING_INTAKE_CONTEXT_PACK_V4` |

旧 question/result/prompt/context/answer bundle 只属于旧 activation。新 activation 的 registry、prompt profile、output schema、policy、Python result、Java formal mapper、前端 answer form 与 stream consumer 必须全部绑定 V4；不做 V1/V3 双读、字段回退或旧 checkpoint 兼容。

`hearing_flow.v2` 的阶段顺序保持不变，但 Intake 两个 stage 的输入/输出 schema binding 与 PARTY_ANSWERS_OPEN 的完成条件必须整体升级。庭审证据官仍使用其当前合同，直到后续 M2/E1 专项重构；本轮不得为其增加猜测性兼容逻辑。

哈希合同也必须显式分域，不能继续由两端各自猜测序列化方式：

- command/action/invocation/proposal/receipt/issue-state/frame-binding/projection 的 V4 canonical hash 统一使用仓库 `ContractJson` 对应的跨语言规范，并提供 Java/Python 共用 golden vectors；
- `case_fact_matrix.v2.content_hash` 保持其已经冻结的 CaseFactMatrix 自哈希算法，Java 只实现同算法的精确镜像，不把它误换成 envelope 的 `ContractJson` hash；
- 每个持久化对象声明 `schema_version` 与 hash domain；同一对象不得同时接受“Python sorted json”与“Java ContractJson”两种结果；
- frame text hash 只覆盖最终 UTF-8 文本字节，live delta 切片方式不参与 hash；manifest/terminal hash 覆盖规范结构，二者不可混用；
- cross-language contract test 必须对非 ASCII、转义字符、空值、decimal、数组顺序和对象键顺序给出固定字节与 SHA-256，任一端不一致即阻止 readiness。

## 17. 后端全链路同步重构清单

### 17.1 Java HTTP、action 与查询边界

| 代码边界 | 必须重构 | 必须删除/禁用 |
|---|---|---|
| `HearingAnswerBundleRequest` | 只建模 V4 question/issue/answer units、hash 与预算 | V1 正则、party-statement union、可空 answers |
| `HearingFlowController` | 只保留 `/answers` V4 入口 | `/statements` 映射 |
| `HearingFlowRuntimeService` | V4 action identity/hash、全覆盖校验、Target-only command admission、双方 SUBMITTED gate | `submitStatement`、answer auto-timeout、targetEpoch-null legacy fallback |
| `HearingFlowActionType` | `QUESTION_SET/ANSWER_BUNDLE` schema pins 改为 V4 | `acceptsSchemaVersion` 对 party statement 的例外 |
| `HearingFlowSubmissionStatus` 与 answer action mapper | answer 路径只产生和接受 `SUBMITTED` | 把 answer 映射为 `COMPLETED/TIMED_OUT` 或 `AUTO_TIMEOUT` |
| `HearingFlowActionRepository`/action SQL loaders | 以 flow、epoch、stage、participant、action type、V4 schema 精确读取唯一 action | 只按 action type 读取后再接受旧 schema |
| `HearingProjectionQueryService`、`HearingFlowView`、controller projection | 返回 V4 question set、answer coverage、M2 ref、issue-state ref 与 projection schema | `issue_set=question_set` 别名、V1 payload 回退 |

正式 answer action payload 必须包含：bundle/action ID、content hash、question set ID/hash、formal issue catalog hash、participant ID/role、按目录有序的 answer unit ID/question ID/issue ID/text、submission status 和 source message refs。下游只读这份规范 payload，不再读取 HTTP DTO 或聊天文本猜测。

### 17.2 Temporal、Target command 与 StageInput

必须同步修改：

- `JdbcTargetHearingPreludeAuthority`、`JdbcTargetHearingRoomStartLoader` 与 bootstrap activities：从证据接待阶段正式 completion/summary authority 冻结并读取唯一 prelude，验证 M1 与 E1 的 case-matrix ID/version/hash 精确一致；缺失、歧义或错 hash 时不得进入 QUESTION；该独立上游合同若形状不变可保持 `hearing-prelude-authority.v1`，这不是 Intake V1 fallback；
- `JdbcTargetHearingAgentStageInputFactory` 在两个 Intake operation 只把 prelude 中的 M1 放入业务 request；可校验 E1 已冻结及其绑定，但不得把 E1 payload、证据原件或 evidence dossier 内容泄露给庭审案情接待官；
- `ContractTypes.CommandType`、`CaseCommandAuthorization`、`AcceptCaseCommand`：增加并只授权 `HEARING_ANSWER_BUNDLE`；新 activation 不接受 `HEARING_STATEMENT`；
- `CanonicalTargetRoomCommandMaterializer`、`TargetTypedRoomCaseProcessDispatcher`、`TargetHearingCommandBridgeActivitiesImpl`：以新 discriminator、V4 action ID/hash、epoch/fence 和 participant authority 形成唯一 canonical command；不得把 V4 command 降级映射为 statement；
- `HearingRoomWorkflowImpl.matchesPartyCommand/processPartyReceipt/processDeadline/formalizeRequiredTimeouts`：回答阶段只接受 `HEARING_ANSWER_BUNDLE + SUBMITTED`，第二份有效 receipt 才推进；answer deadline 不创建 terminal bundle；
- `HearingPartyTerminalReceipt` 的 V4 successor contract：携带 action type/schema/hash 并实行 stage-specific status union；answer 分支在类型层只允许 `SUBMITTED`，Evidence timeout 不再能被 answer 分支接收；
- `JdbcTargetHearingFormalizationActivities`：command/action/party receipt schema exact check 只接受 V4；answer stage 的 timeout formalizer 失败关闭；receipt request hash 覆盖 question/issue catalog hash 与完整 answer content hash；
- `JdbcTargetHearingAgentStageInputFactory`：QUESTION 装载 server-owned slots；ANSWER 装载完整 `hearing_question_set.v4`、formal issue catalog、两条 action ID/hash、answer unit IDs 和 payload，不再只传 `proposal.questions` 与裸 `submission`；
- `JdbcTargetHearingCommandMaterialStore`、`TargetHearingInternalStageMaterializer`、`TargetE2eHearingInvocationPublisher` 与 immutable snapshot publisher：只存取/发布 `target-e2e-hearing-invocation.v4`，request hash 覆盖所有父权威；
- `GovernedTargetE2EHearingInvocationDecoder`：只为新 activation 解码 V4，并把 command invocation context 原样挂入 `HearingGraphInvocation`；
- `TargetHearingRegistrationBundle`、activation manifest 与 operation registry：同时 pin command、invocation、graph/checkpoint、prompt/output/result、stream 和 formal receipt 版本；存在任一 V1/V3 Intake pin 时 readiness 失败；
- `HearingWorkflowStage` 阶段顺序不改，但 readiness 必须证明 ANSWER V4 receipt/transition 和 Evidence timeout receipt 使用不同的 stage-specific 规则。

这条链路中不允许状态别名：HTTP/action/command/receipt/StageInput 对 answer 都使用同一 `SUBMITTED` 语义；`COMPLETED` 仅可作为 AgentRun 或 stage 的生命周期状态，不能冒充 party submission status。

### 17.3 Python Graph、Schema 与 Target executor

| 文件组 | V4 改造职责 |
|---|---|
| `app/schemas/hearing_flow.py` | V4 request/output/result/answer/issue transition/issue state schemas；移除 active Intake V1 union |
| `app/harness/hearing_intake_context_v4.py` | 两个 mode 的唯一有序 ContextPack 与预算失败 |
| `app/agents/hearing_flow.py` | QUESTION baseline issue formal proposal；ANSWER 全量 rebind、新 issue、matrix effects、M2 与 issue-state materializer |
| `app/graphs/hearing/lcel.py`、`state.py` | agent_context sidecar、V4 assembler、V4 checkpoint/version pins，无 V3 fallback |
| `app/graphs/hearing/contracts.py`、prompt registries | result/prompt/model/output/profile pins 全部切换 V4 |
| `app/streaming.py` | generic hidden-manifest gate 与 `json_array_string_prefixes`，公开路径不 coalesce |
| `app/graphs/hearing/target_e2e.py` | 多 frame agent-stream.v3 bridge、terminal frame reconciliation、V4 proposal binding |
| `app/graph_runtime/target_e2e_room_exchange.py` | governed V4 invocation 唯一生产入口，完整保留 sidecar |

旧 `_resolved_issue_mappings`、`_hearing_alignment`、`HearingIssueCoverage.NOT_ADDRESSED` 和 `hearing_case_fact_matrix.delta.v1` 不得继续参与 V4 result。即使旧类因历史测试暂存，也不能出现在 active registry/import path。

### 17.4 agent-stream.v3、完整帧与正式消息

现有 V3 基础设施直接复用并按 V4 operation 重新注册；不新建 Hearing 私有 frame store：

- Python bridge 产生 `PUBLIC_FRAME_START/PUBLIC_TEXT_DELTA/ACTIVE_FRAME_SNAPSHOT/PUBLIC_FRAME_COMMITTED`；
- Java `AgentNdjsonStreamClient` 与 `AgentRunTransientStreamPublisher` 直接转发 start/delta/snapshot，不写 token 事件；
- `PostgresAgentRunV2EventStore` 只在完整 frame commit 时写 `agent_run_public_frame`；
- `AgentStreamOperationRegistry` 的两个 Hearing Intake operation 不再登记 `public_message`，而是 pin `agent-stream.v3` frame contract；
- `HearingPublicTranscriptPolicy.formalPublicText/appendStructuredItems` 不再处理 Intake V4；正式文本只来自已存完整 frames；
- `HearingFormalReceiptTargetCommitPort` 和 `JdbcTargetHearingPublicTranscriptCommitter` 按 frame sequence 逐条创建 room message，不再把 lead + questions 拼成单条终态消息。

新增 `hearing_public_frame_binding_v4`，至少绑定 tenant/case/room/epoch/fence、receipt、agent run/attempt/frame、frame hash、message/publication key 和 ordinal。相同 receipt 严格重放必须逐字验证这些行；不同 frame bytes 失败关闭。

### 17.5 M2、issue state 与 Java 正式化

必须同步重构：

- `TargetHearingFormalPayloadFactory`：QUESTION/ANSWER 的 allowed field set、schema 和 formal projection 全部 V4；
- `ReconciledTargetHearingFormalCommandMapper`：识别 V4 proposal，request hash 同时覆盖 frame manifest、issue transition set、M2 与 final issue-state hash；
- `HearingFormalFinalizer.MatrixKind.INTAKE`、`HearingFormalPayload`、`JdbcHearingFormalFinalizer`：在一个 authority commit 内验证并写入 M2 stage output、独立 issue-state row 和对应 receipt；
- `HearingFormalReceiptTargetCommitPort`：在相同外层事务中先验证 agent-run complete frames，再写正式 frame binding/messages；任何一项失败时 issue state、M2、receipt、messages 全部回滚；
- `TargetHearingAgentRunDomainResultCommitter`、`DurableTargetHearingFinalizationRequestResolver` 与 `ReconciledTargetHearingFinalizationEvidenceResolver`：只解析 V4 terminal material 和同一 run/attempt 的 committed frames，不从 transcript 或旧 proposal 重建结果；
- `JdbcTargetHearingFormalAuthorityLoader`：重放时加载精确 V4 parents 与 hashes，不接受 V1 question/answer、缺失 issue state 或另一 attempt 的 frame authority。

新增 append-only `hearing_issue_state_set` 表，至少保存 issue-state ID/schema、case/flow/stage、question set ID/hash、两个 answer bundle ID/hash、transition hash、M2 ID/version/hash、payload/content hash、agent run 与创建权威。最终 issue-state hash 不参与其所绑定 M2 的自哈希计算，避免循环；二者通过 receipt request hash 原子绑定。

### 17.6 数据库迁移原则

新 migration 必须：

- 只新增后继 migration；不得回改已经应用的 `V035/V037/V067/V069` 文件；
- 更新 `hearing_flow_action` 的 schema check，使历史 V1 行可继续保持 append-only，而新 activation 写入/查询只能命中 V4；应用层不存在 dual-read；
- 为 V4 answer payload 增加数据库可表达的最低结构检查：schema、question set ID/hash、participant、SUBMITTED、1–5 个 answers；完整 issue 顺序仍由 Java 事务校验；
- 创建 `hearing_issue_state_set` 与 `hearing_public_frame_binding_v4` 的 FK、唯一键、hash、cardinality 和 append-only trigger；
- 不修改历史 V1 payload、不回填伪 V4、不把旧 checkpoint 或 old AgentRun 绑定到新表；
- readiness 使用 fresh V4 flow 写入并严格 replay 一次，证明 SQL constraint、Java mapper 和 Python canonical hash 一致。

数据库允许历史 V1 行存在只是账本保存要求；所有新 runtime query 必须以当前 flow/epoch/activation 和 V4 schema 精确过滤，不能写“先读 V4、没有就读 V1”的兼容逻辑。

### 17.7 查询投影、重放、恢复与可观测性

必须同步重构：

- `HearingProjectionQueryService`、`HearingFlowView`、`HearingProjectionAdapter/HearingProjectionSnapshot`：只投影同一 flow/epoch 的 V4 question set、双方 coverage、answer action refs、M2 与 issue-state refs；不得把 `issue_set` 别名成 question set，也不得从旧 artifact 猜测缺失字段；
- projection 必须继续执行 actor/audience 过滤：当前角色可以读取自己的 answer text 与 action ref；对方在综合完成前只能看到 coverage/status，不能读取另一方原始 answer units；正式公共 transcript 只发布模型 public frames，不把私有 answer payload 当作 room message 回填；
- `GET /hearing` 返回显式 `projection_schema_version=hearing-flow-projection.v4`。前端若收到其他版本直接拒绝进入 V4 answer UI，不做字段兼容；
- command replay、AgentRun replay、formal receipt replay 和 `JdbcTargetHearingPublicTranscriptCommitter` replay 都以同一 command/request/result/frame hashes 为键；成功 replay 为 0 Provider 调用，任何字节差异返回幂等冲突；
- recovery/scheduler/reconciler 不得为过期 answer stage 合成 action、receipt、M2 或正式消息；只能报告 `ANSWER_WINDOW_EXPIRED/HEARING_REQUIRED_ANSWER_COVERAGE_INCOMPLETE`；
- 结构化错误至少区分 command schema、answer coverage、stage/epoch/fence、invocation sidecar、manifest/frame、terminal proposal、M2 hash、formal transaction 和 projection authority；日志只记录 ID/hash/阶段/计数，不记录双方回答正文或隐私上下文；
- 指标至少记录 Provider request、raw first delta、first public delta、manifest complete、每帧 complete、model terminal、M2 materialized、formal commit 和 replay hit；这些指标用于局部测试证明流式与正式化顺序，不要求运行浏览器 UAT。

### 17.8 下游自动推进与 activation 门槛

当前 `MatrixKind.INTAKE` 正式提交会立即把 stage 推到 `EVIDENCE_REQUESTS_GENERATING`，并由 Temporal 启动证据官；后续 `TargetHearingTrialDossier` 与 Python `TrialDossierV1` 仍要求 question/answer V1。因此：

- 本轮只完成代码与仓库内 focused/unit/contract/transaction integration tests，证明正式 M2/issue state/frame commit；
- 在 Evidence Officer M2/E1 输入和 dossier 后继合同完成下一阶段重构前，不部署会自动路由正式案件的新 Hearing activation；
- 不允许临时让 Evidence/Dossier 双读 V1/V4，也不允许在 M2 后调用旧证据官“试试看”；
- 本轮不增加 state-machine hold、环境变量暂停开关或仅供测试的 production branch，也不使用浏览器构造只到 M2 的伪 UAT；
- 最终 activation readiness 必须遍历所有可达消费者，确认没有 V1 question/answer/result schema 引用后，才能启用全链路 UAT。

## 18. 实施边界与顺序

以下是一个原子 V4 切换单元的编码顺序，不是允许逐步部署的兼容路线。任一中间状态都不得接入正式案件：

1. 冻结本文所有 V4 schema、字段物理顺序、枚举、预算、canonical/hash domain、prompt/output/profile pins 和 activation reachability 清单；
2. 建立最小 old-red：旧 `/statements` 绕过覆盖、旧 command discriminator、answer timeout 冒充提交、StageInput 丢 action ID/hash、LCEL 丢 `AgentInvocationContext`、M2 不更新 claims、单一 `public_message` 等各选一条决定性失败证据；
3. 新增后继 SQL migration：V4 action constraint、issue-state table、frame binding table、FK/unique/append-only 约束；历史 migration 与历史行保持不动；
4. 同步重构 HTTP DTO、`HearingFlowActionType`、action repository、中央 CommandType/authorization/routing/materializer/dispatcher/bridge，只允许 `/answers + HEARING_ANSWER_BUNDLE + hearing_answer_bundle.v4 + SUBMITTED`；
5. 重构 Temporal party receipt 与回答 deadline 语义：第二份完整 V4 receipt 才推进；覆盖不全/过期不创建 answer action、不创建 AgentRun；
6. 重构 `JdbcTargetHearingAgentStageInputFactory`、command material、invocation publisher/decoder 与 `HearingGraphInvocation`，完整携带 M1、formal question/issue catalog、两份 answer action ID/hash/unit 及 signed `AgentInvocationContext`；
7. 新建 `hearing_intake_context_v4.py`、QUESTION/ANSWER 两套严格 request/output/result schemas 和三个 V4 prompts/few-shots；从 active Intake registry/import path 删除 V1/V3 结构；
8. 实现 QUESTION formalizer：从 server slots 与模型 baseline proposal 生成稳定 question/issue ID、version/state hash 和唯一正式 issue catalog；
9. 实现独立纯函数 issue-transition materializer：使用双方 current answers 全量重绑定全部旧 issue，再创建新 issue，生成不含 M2 binding 的 canonical transition set/hash；
10. 仅在 canonical issue transition set 完成后应用 issue-bound claim/fact/relationship effects，再实现纯函数 M1 → M2、自哈希和 final issue-state binding；
11. 在现有 `agent-stream.v3` 上增加 hidden manifest gate 与 `json_array_string_prefixes`，重构 Hearing Target bridge、operation registry、terminal reconciliation 和 no-retry-after-visible；复用现有 frame store，不新建逐 token 或 Hearing 私有存储；
12. 同步重构 Java formal payload/mapper/resolver/finalizer/authority loader/transcript committer，使 question set 或 issue-state/M2/frames/messages/receipt 在对应事务中原子提交；
13. 重构 GET projection、replay、recovery 与 observability，只读取 V4 authority，不别名、不 fallback、不从 transcript 重建结构；
14. 同步升级前端 API、逐 issue answer form 与 frame store consumer，删除 `submitStatement`、AUTO_TIMEOUT 已回答显示和 `public_message` fallback；只运行组件/contract tests，不操作浏览器；
15. 运行第 19 节局部测试切片，最终证明 `正式 M1 -> QUESTION -> 双方 V4 answers -> ANSWER 绑定解析 -> M2 -> Java 正式化/读取/重放`；
16. 到 M2 正确输出并完成正式绑定解析即停止。本轮不部署新 activation、不创建 UAT 案件、不启动庭审证据官；
17. 后续单独完成 Evidence Officer 的 M2/E1 输入、E2 和 dossier 全链路同版本重构，遍历所有可达消费者后，才允许部署新 activation 并执行第 20 节未来全链路 UAT。

任何一步发现跨语言 Schema 不一致，必须先修正同一 V4 合同的生产者与消费者再继续；不得增加兼容分支绕过。

## 19. 最小决定性测试

本节全部在仓库本地执行，只使用固定、可比较的冻结 M1/E1/receipt/answer fixtures；不得连接运行态 DB、Temporal、真实 Provider 或浏览器。模型边界使用可计数、可分片的 scripted provider adapter 输出冻结 V4 文档；需要数据库语义时使用隔离 transaction integration fixture，不推进真实案件。

formal commit 集成测试在隔离数据库中调用真实 mapper/finalizer/committer，并用 recording workflow relay 截获成功 completion；断言 M2 receipt 已形成后不投递后继 Temporal task。该测试替身只存在于测试装配，不向生产状态机增加 hold 分支。

### 19.1 前序权威、入口、command 与 deadline

- 正向 fixture 从“证据接待阶段正式完成 receipt + 唯一 M1 authority + 已冻结 E1 ref + Hearing prelude”开始；M1 case/version/self hash 或 predecessor receipt 任一错误时 0 Provider 调用；
- QUESTION 业务 ContextPack 只装载 M1，不装载 E1 内容、证据原件或前序私聊；E1 只作为前序阶段已完成的状态权威；
- `/statements` 路由不存在，`/answers` 对 V1/party-statement/缺 issue/hash/空 answer 失败，对精确 V4 成功；
- 中央 authorization、canonical materializer、dispatcher、Target bridge 只接受 `HEARING_ANSWER_BUNDLE`；旧 `HEARING_STATEMENT` 无法到达 V4 workflow；
- answer action/receipt/StageInput 全程只使用 `SUBMITTED`，不存在 `COMPLETED/TIMED_OUT/AUTO_TIMEOUT` 状态翻译；
- 回答 deadline 不创建 action/receipt/AgentRun/M2，投影为明确 expired/incomplete；Evidence timeout 相邻路径保持其独立规则；
- `hearing_flow_action` 新 constraint 接受 V4 SUBMITTED、拒绝 V4 timeout/旧 schema/错误 shape；历史 V1 行保持不可变且 V4 query 永远不选中；
- StageInput 保留两条 action ID/content hash、bundle/unit/question/issue ID 和顺序；decoder 前后 `AgentInvocationContext` 对象值与 signed pins 不丢失；
- Java/Python golden vectors 对所有 V4 hash domain 字节和 SHA-256 完全一致。

### 19.2 QUESTION_GENERATION 与正式 issue catalog

- 正确 stage 只调用一次 Provider，错误 stage 为 0 次；signed provider/model/node/prompt/output/traceparent 全部到达 Harness；
- `lead_public_text` 首 delta 在 manifest、问题结构和终态前可见；manifest 未闭合时 frame texts 不公开，通过后每个可解码 Provider delta 立即公开；
- 1–5 个连续 slot、fact ID、baseline issue、两方位置、alignment、唯一性和预算正向；未知 fact、跳号/重复 slot、缺角色、错 case/M1 hash 负向；
- formal question set 按 slot 生成稳定 question ID、issue ID、version/state hash，并精确绑定 M1 ID/version/hash 与 predecessor receipt；
- 正式 question text 逐字等于对应 frame text，没有 Java 二次拼接；
- QUESTION replay 为 0 Provider 调用，question set、issue catalog、hash 和 frame bytes 完全相同。

### 19.3 回答覆盖、ANSWER_SYNTHESIS、issue state 与 M2

- 两方对每个 issue 都有恰好一个非空 answer unit 时才能封存 bundle；任一缺失、空白、重复、乱序、错 question/issue/role/hash 时 stage 不推进且 0 Provider 调用；
- “一键沿用旧立场”提交展开后的当前文本和新 answer source，不提交 carry sentinel；
- 正好两份 SUBMITTED bundle；跨方 unit、错 action hash 或错 question set hash 在 Provider 前失败；
- 所有旧 issue 按目录顺序恰好 rebind 一次并生成一个公开 frame；current positions 只来自 current answers，baseline 无法注入；
- `REAFFIRM/REPLACE/WITHDRAW/NO_POSITION` 的 source/空值结构正确；模型重判标签，后端不执行关键词、相似度或机械 alignment 复核；
- 双方 `REAFFIRM` 仍生成新 issue version/state hash；`REPLACE` 可使旧 `CONTESTED` 变成模型给出的 `AGREED/PARTIALLY_AGREED`；
- 新单方争议保持 `ONE_SIDED + NOT_PROVIDED + requires_resolution=true`；新共享争议绑定双方 current units 并记录未追加回答轮；
- ANSWER manifest 精确等于“全部旧 issue + 全部新 issue”，数量 1–10；
- existing fact effect 保留 immutable identity；新 fact slot 生成稳定 `FACT_HEARING_*`，relationship 正确解析 new slot；
- 发起方修改 resolution/amount 后 M2 claims 更新；被发起方修改 attitude/alternative 后生成 `RESPONDENT_DIRECT_HEARING` 来源；未被 effect 触及的 claim/fact/relationship 精确保留 M1；
- M2 通过 `CaseFactMatrixV2` 解析和自哈希校验，满足 version=M1+1、parent_ref=M1、matrix kind、source/context hash、fact indexes 和稳定 ID；
- `hearing_issue_state_set.v4` 精确绑定 question set、两份 answer bundle、transition hash 与 M2 ID/version/hash；相同输入/终态产生字节相同 issue state set 与 M2。

### 19.4 流式、正式事务、查询与重放

- 公开字段不经过 75ms coalescer、句子 gate 或终态聚合；Provider 多个 delta 在终态前按顺序到达本地 stream consumer；
- 结构字段不作为聊天 JSON 泄露；每个完整公开字符串只产生一次 frame record，delta 数不增加数据库行数；
- frame text 等于 live deltas 顺序拼接，UTF-8 length/hash 正确；公开后 Provider 失败不自动二次调用；
- QUESTION 的 question set/baseline issues/frames/receipt，以及 ANSWER 的 issue state/M2/frames/messages/receipt，分别在其正式事务边界全成或全退；
- 正式事务失败只重试已保存 terminal material，不重跑 Provider；另一 attempt 的 frame 或同 ID 不同 bytes 失败关闭；
- GET projection 只返回 V4 refs/coverage，不做 `issue_set=question_set` 别名或 V1 fallback；
- USER/MERCHANT 查询投影均只能看到本方原始 answer text；交换角色、未封存与封存后都不会泄露对方 answer payload，公共 transcript 只含 formal public frames；
- successful replay 为 0 Provider 调用并返回相同 question set、issue states、frames、M2 和 receipt hashes；
- 超预算显式失败，不截断 M1、formal issue catalog 或任一方当前回答。

### 19.5 本轮完成条件

只有同时满足以下条件才算“这一段打通”：

1. 固定前序 completion/M1 fixture 能经过 QUESTION 和两方 answer fixture 到达 ANSWER；
2. Python V4 result 可被 Java V4 formal mapper 精确解析；
3. M2 可按 `case_fact_matrix.v2` 重新读取并通过 parent/version/content hash 校验；
4. final issue-state、question set、两份 answer bundles、公开 frames 与 M2 的 ID/hash binding 全部可追溯；
5. 同输入 replay 字节一致且 Provider 调用为 0；
6. 缺 authority、错 ID/hash/role/order、超时或事务失败均 fail closed，且不生成伪 M2；
7. 测试到 M2 正式绑定可读即停止，不部署、不操作浏览器、不启动庭审证据官。

## 20. 未来全链路 UAT 放行门槛（本轮不执行）

只有庭审证据官 M2/E1 输入、E2、dossier 以及所有其他可达消费者完成同版本重构，且 activation readiness 证明不存在 V1/V3 Intake 引用后，才部署新 activation。届时使用 fresh case、保持可比较案情和相同证据材料，按以下门槛验收到庭审接待官稳定生成 M2并继续下游：

1. Intake、Evidence 双方均已正式完成，Hearing deterministic prelude 正常；
2. QUESTION_GENERATION 只调用一次 Provider；
3. 前端先看到真实流式 lead，再看到逐 delta 问题文本；
4. 正式 question set 同时形成 1–5 个稳定 baseline issues，历史双方位置和标签可追溯到 M1；
5. 前端按 issue 展示双方回答入口；立场未变可明确重述或一键展开旧立场；
6. 任一 issue 未得到双方非空回答时无法完成回答阶段，也不会调用综合模型；
7. 两方全量回答封存后，ANSWER_SYNTHESIS 只调用一次 Provider；
8. 前端先看到真实流式综合 lead，随后依正式 issue 顺序看到每个旧 issue 和新 issue 的逐 delta 综合帧；
9. 每个旧 issue 都只使用双方本轮回答形成 current positions、新标签和新 issue version；旧 position 不被静默沿用；
10. 本轮单方新争议保持 `ONE_SIDED/NOT_PROVIDED`，双方都主动涉及的新争议可形成 shared 状态，但不追加回答轮；
11. matrix effects 全部绑定本轮 current issue；
12. `hearing_issue_state_set.v4` 与 M2 同时正式提交，M2 的 version、parent、claims、fact positions、alignment、summary 与 hash 正确；
13. 刷新后只显示正式帧，无重复、无固定话术替换；
14. 成功重放不调用 Provider，返回相同 question set、issue states、frames 与 M2；
15. M2 正式可读作为独立 checkpoint；通过后只能按后续已冻结的 M2/E1→E2 合同继续庭审证据官，不得桥接回旧 Evidence/Dossier 合同。

UAT 必须记录：Provider 请求时间、raw first delta、first public delta、manifest complete、各 frame first/complete、model terminal、issue transition materialized、M2 proposal、Java formal commit 与前端正式刷新时间，以证明首包和帧间流式不是终态后模拟拆字。

## 21. 冻结结论

本计划冻结以下决策：

1. 本轮只重构庭审案情接待官并稳定生成 M2；证据官 M2/E1 交接后置；
2. QUESTION 与 ANSWER 使用独立模式、独立上下文、独立输出 Schema；
3. QUESTION 从唯一 M1 投影形成并正式冻结 baseline issue catalog，ANSWER 不重新划分旧争议；
4. 双方必须对每个正式 issue 各提交一个非空本轮 answer unit，覆盖不完整时不进入 SYNTHESIS、不调用 Provider、不生成 M2；
5. M1/baseline 的旧立场只用于提问、前端参考、历史对照和动作判断，不得充当 current effective position；
6. 每个旧 issue 都只用双方本轮回答全量重绑定、重判标签并生成新 issue version；不存在静默沿用旧立场的运行分支；
7. `REAFFIRM/REPLACE/WITHDRAW/NO_POSITION` 都必须有本轮 answer source；前两者生成 current position，后两者明确为空；
8. 一方首次提出且对方没有回应机会的新争议固定为 `NEW_UNILATERAL_ISSUE/ONE_SIDED/NOT_PROVIDED`，不从沉默推定态度；双方当前回答都明确涉及时才可成为 `NEW_SHARED_ISSUE`，且不追加回答轮；
9. canonical issue transition set 必须先物化；claim/fact/relationship 只能作为绑定 current issue 的 matrix effects；M2 生成后再封装绑定其 ID/version/hash 的最终 issue state set；
10. 输出采用单一顶层 `frame_manifest`，取消逐帧 Header；ANSWER manifest 包含全部旧 issue 与新 issue，为 1–10 帧；
11. `lead_public_text` 与 `frame_texts` 真正逐 Provider delta，旁路应用层 coalescer，不等待句子、字符串闭合、结构终态或 M2；
12. 模型自然语言和业务语义受信任；后端不做正则、相似度或机械标签重判，只严格校验 Schema、ID、角色、回答来源、覆盖顺序、预算、幂等、重放、状态机和 hash；
13. M2 由后端从 M1、正式 issue transition set 和 matrix effects 确定性生成，完整保留父子链、来源、版本与 hash；
14. 旧事实身份不可改，修正通过新事实及 `CORRECTS/QUALIFIES/DUPLICATES` 关系表达；
15. delta 不落库，完整公开帧一次持久化，完整终态通过后与 question set 或 issue state set/M2 同事务提升为正式消息；
16. 首个公开 delta 后禁止自动重调 Provider；事务失败只重试已保存终态的正式化；
17. `/answers`、中央 command、Temporal receipt、StageInput、Target invocation、Python graph/result、stream、Java formalizer、SQL、projection 和 replay 作为一个后端切换单元同步重构；不是在旧链路上增加新字段适配层；
18. 新 activation 单版本切换，不兼容旧 Hearing Intake output/checkpoint/answer bundle；系统安全提示词、数字人提示词和 Hearing 状态机阶段顺序保持不变；
19. 本轮局部测试只打通正式 Evidence completion/prelude/M1 到 M2 正确输出、解析、绑定和重放，不部署、不做浏览器或正式 UAT、不启动庭审证据官；
20. E1 只在 prelude 边界验证其已冻结且精确绑定 M1，不进入庭审案情接待官业务 ContextPack；
21. 下游 Evidence Officer/E2/dossier 未完成同版本重构前，activation readiness 必须失败，不得以 dual-read、字段 fallback 或旧消费者兼容临时放行。

若实施中发现目标 Provider 无法按 `lead_public_text -> manifest -> frame_texts -> structures` 顺序产生可增量解析的结构化 JSON，必须先形成可复现证据并重新进行机制设计审查；不得用等待模型终态、前端模拟打字、固定话术、第二次模型调用、case-specific bypass 或放宽正式 ID/角色/状态校验代替本计划。
