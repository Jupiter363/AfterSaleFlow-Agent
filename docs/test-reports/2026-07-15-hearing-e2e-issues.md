# 庭审功能 E2E 问题清单

## 测试范围

- 测试日期：2026-07-15
- 测试案件：`CASE_bde70a2db4d7469582d5ea5e058db747`
- 测试角色：`user-local`、`merchant-local`、`reviewer-local`
- 覆盖链路：三轮庭审、庭审补充证据、裁决草案、冻结审核包、平台终审入口
- 测试方式：浏览器操作、Java/Python 服务运行记录及 PostgreSQL 持久化结果交叉核验

## 测试结论

三轮庭审可以收敛并生成裁决草案，但当前链路无法可靠完成到执行阶段。主要阻断点是庭审补充证据核验返回 HTTP 422、裁决草案结构损坏，以及终审决定未成功落库。

## 阻断问题

### HEARING-E2E-001 庭审补充证据核验失败但前端显示成功

- 严重度：阻断
- 状态：已修复，待下一次完整庭审 E2E 复验
- 实际结果：双方第 2 轮提交证据后，页面显示已提交证据材料，但证据书记官运行均失败。
- 运行记录：
  - `AGENT_RUN_e2c9321a9dd447158b9cff0c859c338b`：`HTTP 422`
  - `AGENT_RUN_13dfd77e1a0c44779031a9bd22e94fb0`：`HTTP 422`
- 影响：法官继续基于未成功核验的证据进入后续轮次，证据矩阵没有包含庭审补充证据的有效核验结果。
- 期望：同一提交批次的全部证据并行核验，全部任务收敛后一次性合并双方共享证据矩阵；任一任务失败时前端明确显示失败和重试状态。
- 根因：Java 在庭审补证请求中发送 `room_policy.room_type=HEARING`，Python 契约原先只允许 `EVIDENCE`，因此在进入模型前返回 422。
- 修复：Python 接受 `EVIDENCE | HEARING`；Java 保留双方隔离的证据会话，同时把庭审处理房间统一投影为 `HEARING`。前端支持最多 50 份材料并行上传，并仅提交一个包含完整 `attachment_refs` 的批次。Java 对整批评估做一对一覆盖校验，在同一事务中保存全部核验结果，完成后只冻结一个新版共享证据卷宗和证据矩阵。
- 聚焦验证：Python 44 项、Java 23 项、庭审前端 52 项测试通过；前端生产构建通过。

### HEARING-E2E-002 裁决草案结构损坏

- 严重度：阻断
- 草案：`DRAFT_a8a01af0a7904a42b4867761dd8bb202`
- 实际结果：`reviewer_attention_json` 为 `["],", "],", "],", "],", "],", "]"]`。
- 影响：草案页显示 6 条损坏的 `]`，终审页则显示关注项为 0。
- 期望：严格按数组 Schema 持久化完整的审核关注事项，冻结包投影保持一致。

### HEARING-E2E-003 草案缺少结构化事实认定和规则适用

- 严重度：阻断
- 实际结果：`fact_findings_json=[]`、`policy_application_json=[]`。
- 已有上游结果：争点提炼节点和证据交叉核验节点均已生成结构化结果。
- 影响：分析节点结果没有形成可审核、可追溯的裁决依据映射。
- 期望：草案必须包含争点、事实认定、证据映射、规则适用和处理建议的完整关联。

### HEARING-E2E-004 规则适用节点未取得规则

- 严重度：阻断
- 实际结果：`rule_application_node` 返回 `missing_policy=true`、`applications=[]`。
- 影响：草案在没有规则条款的情况下给出责任兜底及处理建议，缺少可审计依据。
- 期望：未取得适用规则时停止自动形成处理建议并转入明确的规则缺失状态。

### HEARING-E2E-005 终审决定未成功落库

- 严重度：阻断
- 实际结果：填写理由并选择批准后，确认状态未保持，未形成终审决定记录。
- 数据库状态：`review_task=IN_REVIEW`、`decision_json={}`、`human_review_record=0`。
- 影响：案件无法进入执行助手阶段，端到端链路不能闭环。
- 期望：确认提交后生成不可变审核记录，并将案件推进到执行阶段；失败时保留表单内容并展示可重试错误。

## 严重问题

### HEARING-E2E-006 第一轮出现迟到的重复开场

- 第一轮于 16:35:17 关闭，第二轮已经开启；法官在 16:35:47 再次发送“现在开启第1轮”。
- 期望：轮次提示必须在轮次开放时产生，迟到结果不得插入已关闭轮次。

### HEARING-E2E-007 第二轮缺少明确开场和提问

- 实际结果：时间线直接进入证据提交和用户陈述，直到轮次结束才出现封存消息。
- 期望：第二轮开放时明确说明本轮争点、待补证事项、双方任务和截止时间。

### HEARING-E2E-008 法官消息泄漏损坏的结构化文本

- 实际文本尾部：`；证据矩阵无新增有效关联。；],；confidence`
- 期望：模型结构化输出只能经过字段级渲染，不得直接拼接残缺 JSON 文本。

### HEARING-E2E-009 第三轮正常提交被标记为强制关闭

- 实际结果：用户和商家均通过 `PARTY_ACTION` 主动提交“认可方案”，轮次状态仍为 `FORCED_CLOSED / MAX_ROUNDS`。
- 期望：双方正常完成最后一轮时记录为 `COMPLETED`；只有异常中止才使用 `FORCED_CLOSED`。

### HEARING-E2E-010 陪审团报告未形成正式版本

- 实际结果：庭审消息中存在包含 6 项 findings 的 `JURY_REVIEW_REPORT`，但冻结审核包为 `deliberation_report_version=0`。
- 伴随问题：报告没有 `confidence` 字段，前端将缺失值显示为 `0/100`。
- 期望：评审报告先通过 Schema 校验并持久化版本，再进入草案和冻结审核包。

### HEARING-E2E-011 最终庭审分析耗时过长

- 运行：`AGENT_RUN_cddbf488598545539e9c0ae27075d35d`
- 耗时：`194612 ms`
- Token：`109748`
- 影响：页面约 3 分 15 秒没有有效进度反馈，用户容易判断为流程卡死。

### HEARING-E2E-012 等待草案时倒计时语义错误

- 实际结果：已经进入 `JUDGE_DRAFTING`，页面仍显示“当前轮次还剩 00:00”。
- 期望：改为裁决草案生成状态和节点进度，不再展示轮次倒计时。

### HEARING-E2E-013 草案版本显示不一致

- 数据库版本：`v4`
- 法官正文：`V2 草案`
- 前序第三轮消息：`V1 草案`
- 期望：业务版本、持久化版本和展示文案引用同一个版本来源。

### HEARING-E2E-014 庭审房间生命周期未关闭

- 实际结果：案件已进入 `current_room=REVIEW`，但对应 `case_room` 仍为 `HEARING / OPEN`。
- 期望：庭审完成并生成草案后，庭审房间应封存或关闭，避免产生陈旧可进入状态。

### HEARING-E2E-015 hearing_flow.v2 失败任务无法跨持久化边界恢复

- 严重度：阻断（中断恢复链路）
- 状态：已复现；按 2026-07-16 E2E 决策暂时跳过，未修复，不计入当前主链路通过项
- 复现案件：`CASE_732d4e8fbcbd467e86aa06a4f97eaab7`
- 失败阶段：`INTAKE_SYNTHESIZING`，原始 AgentRun 因 `AGENT_SERVICE_UNAVAILABLE` 进入 `FAILED`
- 实际结果：恢复调度调用 `retryInfrastructureFailure` 时抛出 `IdempotencyConflictException: Idempotency-Key was already used for a different agent run`，任务无法恢复
- 数据证据：数据库比较 `hearing_flow_stage.input_json = agent_run.stream_request_json` 为 `true`，请求在 JSON 语义上相同
- 根因：首次请求对未规范化的 JSON 序列化字节计算 SHA-256；请求写入 PostgreSQL `jsonb` 后对象字段顺序可能变化，恢复时重新序列化得到不同 hash，被幂等保护误判为不同请求
- 迁移关系：V036 仅调整证据完成唯一键，不修改 AgentRun 请求或 hash；服务重启只触发了对存量失败任务的扫描，并非迁移破坏恢复数据
- 建议修复：新请求使用递归字段排序后的规范化 JSON 计算 hash；兼容存量记录时，hash 不一致后追加 `JsonNode` 语义等价校验，语义相同则允许创建恢复尝试
- 待验证：进程重启后自动恢复、定时调度重复扫描幂等、真实请求变化仍能触发冲突、存量非规范化 hash 兼容

### HEARING-E2E-016 SSE 客户端断连触发通用 JSON 异常处理

- 严重度：一般（运行日志与异常边界）
- 状态：已复现；按 2026-07-16 E2E 决策暂时跳过，未修复，不阻断当前主链路
- 复现场景：证据页面刷新或替换 EventSource 连接后，案件事件心跳向旧连接发送数据
- 实际结果：`CaseEventService.heartbeat` 捕获 `java.io.IOException: 你的主机中的软件中止了一个已建立的连接`；Servlet 异步派发随后进入通用异常处理器，后者尝试以已提交的 `text/event-stream` Content-Type 写入 `ApiResponse`，继发 `HttpMessageNotWritableException`
- 业务影响：本次 `user-local` 举证完成记录已提交并可查询，页面状态正确；异常未导致业务事务回滚，但产生误导性 ERROR 堆栈并污染运行监控
- 建议修复：识别已提交 SSE 响应上的客户端断连并直接结束异步请求，不进入通用 JSON 异常响应；不能宽泛吞掉普通上传或持久化路径中的 `IOException`
- 待验证：主动刷新、路由切换、EventSource 自动重连、网络中断、正常非 SSE `IOException` 仍映射为真实服务错误

### HEARING-E2E-017 单边问题集导致另一方无法陈述并破坏同步公平性

- 严重度：阻断（hearing_flow.v2 双方回答阶段）
- 状态：已复现；2026-07-16 已确认按 AI Native 对话式方案重构
- 复现案件：`CASE_ea2dc9d7a3df470e9bd2bdee038047a1`
- 实际结果：接待官生成的 5 个问题全部 `target_roles=[MERCHANT]`；商家提交后，`user-local` 页面仍显示“用户未提交”，但用户没有问题、陈述输入或终态按钮，只能等待共享截止时间超时
- 伴随公平性问题：商家原始回答在用户提交前已经出现在用户庭审时间线，后提交方可以针对先提交内容调整陈述
- 根因：模型与持久化只约束“全局至少一个问题”，没有建立共享争议点与双方陈述机会；前端又以 `applicableQuestions.length > 0` 作为提交入口条件，并把逐题表单当成唯一输入方式
- 目标交互：双方不逐题填写表单，而是在共享争议点提示下分别提交自然语言陈述；接待官在双方终态后把陈述映射到各争议点
- 目标契约：最多 5 个共享 `issue_id`，每个争议点包含双方视角化 prompt；问题分配、提交幂等和终态以 `participant_id` 为准，角色仅作为语义与审计快照
- 隔离要求：一方提交后只显示本人提交确认；双方提交或超时前不得向另一方展示原始陈述；终态后公开接待官按 `issue_id` 生成的双方立场、依据、未回应项与证据引用
- 兼容要求：存量 `hearing_question_set.v1` / `hearing_answer_bundle.v1` 仍可被读取和合并；零目标问题的空提交仅作为异常兜底，不作为正常生成策略
- 待验证：双方提交顺序互换、相同角色快照下身份 ID 隔离、单方超时、服务重启、每个争议点双向覆盖、对方陈述延迟互显、当前存量案件继续推进

### HEARING-E2E-018 接待综合请求遗漏顶层 participant_id 导致 HTTP 422

- 严重度：阻断（hearing_flow.v2 双方自然语言陈述综合阶段）
- 状态：2026-07-16 已按方案 1 完成双端兼容修复；原案件保留为缺陷现场，等待全新案件 E2E 复验
- 复现案件：`CASE_1668f0b20437480c805c7e0ddbc9bbb2`
- 失败运行：`AGENT_RUN_e56b393783104e82bd453799c4f0b7ea` / `HEARING_INTAKE_SYNTHESIS` / `HTTP 422`
- 前置验证：接待官生成 3 个共享争议点及 USER/MERCHANT 视角提示；`merchant-local`、`user-local` 两条 `hearing_party_statement.v1` action 均按身份 ID 独立落库；先提交的商家原始陈述在用户提交前不可见
- 实际结果：双方提交后双边门正常关闭，但 Java 发送的 `party_submissions[0]` 和 `[1]` 缺少顶层 `participant_id`；Python `HearingIntakeSynthesisRequest` 分别返回 `Field required`
- 数据形态：身份 ID 已存在于各项嵌套的 `submission.participant_id`，说明持久化动作正确，错误发生在 Java Agent 请求投影层，不是数据丢失或竞态
- 根因位置：`HearingFlowRuntimeService.partySubmissions` 只写入顶层 `participant_role`、终态、来源和 submission；Python `HearingPartySubmission` 将顶层 `participant_id` 定义为必填并据此校验两个身份不同
- 推荐修复：Java 在每个 party submission 顶层写入 `participant_id=party.participantId()`，补充运行时请求断言；Python 增加仅面向存量请求的兼容提升逻辑，从嵌套 `submission.participant_id` 提升到顶层，同时继续强制非空和双身份唯一
- 已实施：Java 合成请求投影已补齐顶层 `participant_id`，并断言 USER/MERCHANT 两个身份；Python 仅在顶层缺失或空白时从嵌套 submission 提升身份，不从 role 推导，显式顶层身份保持权威
- 聚焦验证：Java `HearingFlowRuntimeServiceTest` 5/5 通过；Python `test_hearing_flow_v2.py` 17/17 通过，Ruff 通过
- 恢复建议：最小风险方案是修复后导入新案件重跑；若必须保留本案，需要显式重建失败阶段请求并新建 AgentRun，且需同时处理已登记的 `HEARING-E2E-015` 请求哈希恢复问题
- 待验证：双方提交顺序互换、顶层身份投影、嵌套身份兼容、重复身份拒绝、接待官逐争议点双向映射、失败阶段恢复或新案件重跑

### HEARING-E2E-019 接待首轮结构化输出遗漏累计摘要导致流式结果失败关闭

- 严重度：阻断（全新案件首轮接待）
- 状态：2026-07-16 已按方案 1 完成结构化 Schema 修复；等待全新案件 E2E 复验
- 复现案件：`CASE_da6420877e324ab583590ed2abb7c4d2`
- 失败运行：`AGENT_RUN_b16c133297d24afebe3f81c6241d25b1` / `INTAKE_TURN` / `AGENT_OUTPUT_SCHEMA_INVALID` / `retryable=false`
- 实际结果：模型已经流式输出首轮提问、订单/售后/物流引用、平台观察、诉求和核心冲突，但没有输出 `case_detail.case_story.one_sentence_summary`；最终 Pydantic 校验失败，页面显示“接待官生成失败”，输入及受理按钮被锁定，且未写入兜底卷宗
- 精确错误：`Value error, case_detail.case_story.one_sentence_summary is required`，节点为 `intake_turn_case_detail`，模型原始响应长度 3554 字符
- 根因位置：`IntakeCaseDetailLlmOutput.case_detail` 当前声明为 `dict[str, Any] | None`；发给模型供应商的 strict JSON Schema 因而只是 `object + additionalProperties=true` 或 `null`，无法在生成阶段约束嵌套 `case_story.one_sentence_summary`，只有流结束后的 model validator 才发现遗漏
- 推荐修复：把 `case_detail` 改为显式结构化补丁模型，并将 `case_story.one_sentence_summary` 声明为模型 Schema 层必填；保留现有最终 validator 作为第二道防线，同时增加 `model_json_schema()` 必填断言和真实流式缺字段回归用例
- 已实施：使用 `TypedDict + Required/NotRequired` 定义 `IntakeCaseDetailPatch` 与 `IntakeCaseStoryPatch`；供应商 Schema 现强制 `case_detail -> case_story -> one_sentence_summary`，摘要带 `minLength=1`，后置校验继续拒绝纯空白；现有增量分支和 `event_timeline` 保持兼容，解析结果仍为普通 `dict`
- 聚焦验证：`test_intake_case_detail_dossier.py` 21/21 通过，Ruff 与 `git diff --check` 通过
- 备选修复：仅对“摘要单字段缺失”从可信表单、上一版摘要与当前消息做确定性补全；该方案可恢复流程但会掩盖模型契约违约，不建议作为主路径
- 不建议：流式可见内容已经发出后再发起第二次模型纠错调用，会造成重复话术、可见输出与最终卷宗来源不一致
- 恢复建议：修复后重新导入全新案件；当前失败 AgentRun 为非重试态，继续复用本案还需要单独设计显式重试语义

### HEARING-E2E-020 证据书记官结构化流提前结束导致本轮核验失败关闭

- 严重度：阻断（用户证据核验）
- 状态：2026-07-16 已按方案 1 完成代码修复与聚焦回归；保留原案件作为缺陷现场，等待全新案件 E2E 复验
- 复现案件：`CASE_78af57b0bb6f4d4bbe9195d4edb05ca6`
- 失败运行：`AGENT_RUN_c9d393133d4e4a9aa573c9d63da5da01` / `evidence_turn` / `AGENT_OUTPUT_SCHEMA_INVALID`
- 前置状态：`merchant-local` 已按身份 ID 独立提交并完成举证；`user-local` 的 `EVIDENCE_a5231d6fcacc4bf09f3fe3229d31c9f4` 已上传并提交批次
- 页面结果：先流式显示部分核验意见，随后显示“模型响应未完成，当前不会写入兜底内容；请在服务恢复后重试”，并弹出“数字人生成失败”
- 精确错误：`EvidenceTurnLlmOutput` 收到 1735 字符后在第 54 行第 6 列结束，Pydantic 报 `Invalid JSON: EOF while parsing a string`；原始尾部位于 `human_review.reason_codes` 字段
- Python 日志：`AgentOutputSchemaError: evidence_turn returned invalid streamed structured output`，`agent_invocation_id=AGENT_INVOCATION_ae6ef141bc764b6e971072f8f93e38a3`
- 持久化终态：`run_status=FAILED`、`stop_reason=STREAM_FAILED`、`error_retryable=false`、`visible_output_emitted=true`；用户证据没有生成 `evidence_verification` 记录
- 根因边界：模型供应商的 strict JSON Schema 流仍返回了提前结束的不完整对象；`LiteLlmProxyClient.generate_stream` 只累计 `delta.content` 并在流结束后做 Pydantic 验收，当前未记录或强制校验 `choices[0].finish_reason`，也没有在已公开可见增量后安全重试的协议
- 非根因：同时间 Java 的客户端断连 `IOException` 属于已登记的 `HEARING-E2E-016`；Python 已独立记录结构化输出失败，不能把本次业务失败仅归因于浏览器断连
- 推荐修复：将 `evidence_turn` 纳入验证后再公开的 buffered structured 节点，复用现有非流式 strict Schema 调用及一次兼容重试；只有完整对象通过 Pydantic 和业务护栏后再投影 `room_utterance`，避免部分可见内容与最终失败状态不一致
- 配套增强：采集并校验流终态 `finish_reason`，把 `length/content_filter/provider_disconnect` 映射为可诊断错误；补充供应商截断流、非法 JSON、重试成功、重试仍失败、不得持久化部分评估的回归用例
- 已实施：`evidence_turn` 改为非流式完整缓冲；首次 strict JSON Schema 结果解析或校验失败时整份丢弃，并以纯 JSON 模式重试一次；模型层不再发布 `visible_delta`，只有工作流通过证据引用、真实性与非定责业务护栏后，才由最终响应公开清洗后的 `room_utterance`
- 终态诊断：通用结构化 SSE 现在必须同时收到 `[DONE]` 与 `finish_reason=stop`；缺失 `[DONE]`、缺失终止原因、`length`、`content_filter` 和其他 provider 终止原因分别映射为明确服务错误，不再把所有提前结束统一表现为最终 JSON EOF
- 聚焦验证：Python `tests/test_llm.py`、`tests/test_streaming.py`、`tests/agents/test_evidence_clerk_turn.py` 共 69/69 通过；覆盖截断后重试成功、半截 JSON 零可见输出、业务护栏失败零可见输出、成功后仅最终清洗结果公开；Ruff 与 `git diff --check` 通过
- 不建议：直接补括号或从残缺 JSON 猜测 `human_review`、矩阵和内部交接字段；也不建议在当前失败状态下直接点击“我方举证完成”，否则会绕过本轮证据核验产物
- 完成门禁缺口：当前前端和 Java 仍允许直接点击“我方举证完成”；这样会把缺少核验与事实映射的材料按 `UNVERIFIED / unmapped_evidence` 封卷并立即开放庭审，不能作为有效 E2E 结果
- 恢复结论：当前没有证据 AgentRun 的用户重试入口，普通恢复调度也不会领取该非重试失败；仅修复并重启不能恢复本次运行。推荐保留本案作为缺陷现场并从新案件重新执行；若必须续本案，需要额外实现审计化的“重新核验”动作和完成门禁

### HEARING-E2E-021 Codex 宿主异常期间前端开发服务被连带终止

- 严重度：环境阻断（仅前端开发服务）
- 状态：2026-07-16 已定位并通过单独重启前端恢复；Java、Python、数据库和当前案件未重启
- 页面结果：右侧浏览器访问 `http://127.0.0.1:5173/` 显示“拒绝建立连接”
- 精确状态：`5173` 无监听，`.local-dev/frontend.pid=28948` 对应进程已退出；`8080` 和 `18000` 仍正常监听
- 前端日志：Vite 曾正常启动，随后 `pnpm dev` 报 `ELIFECYCLE Command failed with exit code 3221226505`，即 Windows 状态码 `0xC0000409`；没有 Vite/应用代码异常堆栈
- 环境证据：同一时间段 Windows Application Error / WER 连续记录 Codex `ChatGPT.exe` 异常退出，异常码 `0xc06d007f`；时间上与 Browser 会话丢失和前端子进程结束吻合
- 根因边界：当前证据支持“宿主异常或进程树生命周期导致开发服务被终止”，不支持把它归因于前端业务代码；早期 `ECONNREFUSED 127.0.0.1:8080` 发生于 Java 启动窗口，并非最终退出原因
- 恢复验证：仅重启前端后，`5173/@vite/client`、前端首页、Java health、Python health 均返回 `200`，新前端监听 PID 为 `13756`
- 后续建议：为本地开发服务增加独立监督/自动拉起与 PID 失效检测；Codex 宿主崩溃需在客户端侧单独提交 WER 证据排查

## 已验证正常项

- 三轮双方提交记录可以落库。
- 第二轮商家超时自动提交生效。
- 裁决草案和冻结 `ReviewPacket v1` 可以生成。
- 审核员可以打开终审室。
