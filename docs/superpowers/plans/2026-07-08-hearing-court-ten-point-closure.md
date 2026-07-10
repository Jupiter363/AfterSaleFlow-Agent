# Hearing Court Ten-Point Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把庭审页从“可展示的 UI”补齐为可运行、可追溯、可审核的三轮 AI 小法庭产品闭环。

**Architecture:** Java 后端负责庭审状态机、权限、证据/案情装卷、庭审卷轴、裁决草案和审核入口；Python agent 负责法官/评审团的结构化推理；Vue 前端只消费后端状态合同并展示可操作按钮，不自行猜测阶段。开庭前新增硬性准入门槛：发起争议方必须至少正式提交 1 份相关证据，否则证据室不能封存、不能自动超时进入庭审、不能触发开庭装卷。第一阶段优先落地状态合同、庭审完成语义、裁决草案入口、卷轴可追溯和开庭准入规则；后续阶段继续加深法官智能、实时协作和可解释裁决。

**Tech Stack:** Java Spring Boot + JPA + Flyway + PostgreSQL；Python FastAPI agent harness；Vue 3 + Vitest；SSE room events。

---

## 2026-07-09 产品语义补充

本轮确认以下规则，并作为后续开发的主线约束：

1. **一致方案/和解暂不进入正常庭审主线。** 既有代码和 UI 可以保留作为后续独立功能入口，但当前三轮审理不展示、不提交、不依赖“一致方案/和解”。第三轮只做“对法官拟处理方向的确认或说明异议”。
2. **本轮已封存时，输入台不再像可编辑组件。** 当前轮封存后不显示 textarea，不显示“提交陈述”按钮，只显示锁定态；三轮结束后输入台变成小状态：“庭审已封存，等待裁决草案”。
3. **庭审完成按钮改为裁决草案入口。** 右侧按钮文案为“查看裁决草案”；只有后端 `can_complete_hearing=true` 或等价草案 ready 状态时才允许进入 outcome/draft 房间。未 ready 时只提示等待裁决草案。
4. **草案房间承担结果查看和审核员确认/修改。** 用户和商家在草案房间查看 AI 法官输出的裁决方案草案；审核员在该房间确认或修改最终方案。解释员不出现在庭审页，而是在草案/审核阶段复盘整场庭审，并向审核员解释最终方案依据。
5. **庭审阶段允许补证，但重点在第二轮。** 第二轮证据解释阶段允许双方补充证据；补证提交后立即对对方可见，并触发证据书记官二次复核。
6. **证据书记官复核必须更新 active evidence dossier。** 第二轮补证/解释完成后，证据书记官基于新材料更新原证据矩阵，生成新的 active dossier/version；法官后续上下文不能继续使用旧的固定案卷，而必须重新拼接最新 active 版本。
7. **内部通信数据不暴露给当事人前端。** evidence matrix version、A2A 原始消息、agent 内部通信、审计 visibility 等仅供后端和 agent 上下文使用；当事人界面只展示中文化、业务化后的消息或卡片。
8. **A2A 可视化分层。** 陪审团静默观察报告写入数据库但不在法庭界面展示；正式复核报告以中文卡片进入聊天/庭审记录，同时该正式报告也要注入法官上下文，用于下一轮问题或最终裁决草案生成。
9. **继续增强庭审卷轴。** 卷轴需要记录轮次开启/封存时间、双方提交状态、超时/自动封存原因、补证引用、证据矩阵版本变化摘要、法官问题/小结、陪审团正式复核报告、裁决草案引用，形成后续审核和申诉可追溯档案。

## 2026-07-10 单实例导入与固定演示账号边界

1. **当前部署边界是单个 Java 实例。** 所有直接导入和模拟导入共用一个进程内公平全局门闩；门闩等待发生在单项数据库事务之外，进入后只执行一条 `case + room + participants + initial intake turn` 原子导入。
2. **当前不使用 PostgreSQL advisory lock、分布式锁、3 秒冷却或 429 限流。** 这个方案只保证同一 Java 进程内全局串行；未来扩展到多个 Java 实例前，必须升级为数据库锁或分布式协调，不能沿用进程内门闩宣称跨实例安全。
3. **每次请求只能导入一条。** `SimulateImportRequest`、Java application command 和 Python simulator contract 的 `count` 固定为 `1`；Python/其他 simulation client 若返回非 1 条，Java 必须按输出 schema 异常明确拒绝，不能截断或静默批量导入。
4. **导入只接受固定演示双方账号。** `USER=user-local`、`MERCHANT=merchant-local`。直接导入中的 `user_id` / `merchant_id` 不匹配时返回 400，不做静默改写；public/internal simulate 的 current/counterparty actor 也必须与 initiator role 按这两个固定账号严格对应。
5. **当前审核侧只有唯一系统审核员。** 只有 `actor_id=reviewer-local` 且 `role=PLATFORM_REVIEWER` 可以确认、修改或提交审核决定；其他账号即使声明 `PLATFORM_REVIEWER` 角色，也必须返回 403。决定权限要在查询 review task、draft 或其他案件审核资源之前校验，避免通过 404/响应差异泄漏任务是否存在。审核列表与审核包的查看权限继续沿用既有角色规则，本轮不扩展多审核员分配模型。
6. **固定账号建立在可信本地演示认证边界上。** 当前 `X-User-Id` / `X-Role` 头仅用于本地调试和固定三账号角色切换，不构成面向公网的强身份认证。Docker 服务对外发布前，必须由可信认证网关或签名会话提供并覆盖身份，不能允许外部客户端自行声明这两个头；本轮不把完整登录、多用户、审核员分配引入庭审主线。
7. **dev-local 调试边界。** 本地联调以 `scripts/dev-local.ps1` 启动 Java API `8080` 与 Vite `5173`；Docker 只承担依赖服务和最终部署形态，不作为日常前后端热调试入口。

---

## 十点覆盖矩阵

| 点位 | 产品目标 | 第一阶段落地点 | 后续深化 |
|---|---|---|---|
| 0. 开庭准入硬门槛 | 发起争议方必须先正式提交相关证据，否则争议不受理进入庭审 | `complete` 与 `expire` 双入口后端强校验；前端点击“举证完成”本地弹错；catalog 透出 `initiator_role` | 后续可加入“不予受理/退回补证”状态和审核员可人工放行策略 |
| 1. 法官智能 | 法官基于案情事实地图 + 最新证据矩阵 + 三轮陈述提问/裁决 | 保证法官上下文读取 bootstrap snapshot，并在后续轮次重新拼接 active evidence_dossier | Python judge prompt 加“裁判路径”、第三轮方案确认和 JSON contract 回归 |
| 2. 三轮状态机 | 三轮事实陈述/证据解释/方案确认稳定流转 | 新增状态视图 `hearing_phase`、`next_step_hint`、`can_complete_hearing` | 后端定时/双方提交后自动推进 judge turn |
| 3. 证据证明矩阵 | 法官不是看文件列表，而是看事实-证据关系 | 开庭冻结 baseline version；第二轮证据书记官复核后生成 active version | 前端卷轴展示 fact-evidence matrix 版本变更摘要 |
| 4. 接待卷宗质量 | 接待室输出“事实地图”而非单段摘要 | 装卷时保留 intake dossier 原结构并在庭审宣读中中文化 | 接待官输出 contract 扩维回归 |
| 5. 评审团节点 | 评审团通过 A2A 做合议复核，不直接裁决 | 第 1/2 轮静默 note 给法官；第 3 轮后正式 report | Python jury review contract + `agent_a2a_message` 存储 |
| 6. 可追溯卷轴 | 所有开庭宣读、陈述、证据引用、法官轮转可入库追溯 | 卷轴按真实 rounds/messages 展示，不用 mock；补状态语义 | 增加 ledger endpoint 汇总消息、证据、摘要、hash |
| 7. 权限/session | 双方隔离，审核员全权限 | 沿用现有 session/actor 权限；庭审 shared evidence 仅限提交后可见 | 将新增 endpoint 全部走 AuthenticatedActor 校验 |
| 8. “庭审完成”语义 | 按钮不应直接跳最终页；必须以后端状态为准 | 新增/使用后端完成状态，前端在未 ready 时提示等待 | 必要时加入 party readiness 信号 |
| 9. 实时协作 | 双方提交/法官处理/审核入口通过 SSE 更新 | 页面已有 SSE；状态合同加入可刷新字段 | 补事件类型 `HEARING_PHASE_CHANGED` |
| 10. 裁决解释 | 输出确定裁决草案、证据采信、规则适用和审核关注 | 完成状态暴露 latest draft / review gate | Outcome/Review 页面展示裁决解释链 |

---

## 文件结构

- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceCompletionService.java`
  - 在 `complete(caseId, actor, idempotencyKey)` 和 `expire(caseId)` 进入封存/开庭前校验发起争议方正式提交证据数量。
  - 无证据时抛出中文业务错误：“发起争议方需先正式提交至少 1 份相关证据，当前证据栏为空，暂不能进入下一步。”
- Modify: `java-api-service/src/main/java/com/example/dispute/infrastructure/persistence/repository/EvidenceItemRepository.java`
  - 增加按 `caseId + submittedByRole + SUBMITTED + deletedAt is null` 统计正式证据的方法。
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceCatalogService.java`
  - catalog 返回 `initiatorRole`，供前端判断当前身份是否为发起争议方。
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/RoleScopedEvidenceView.java`
  - 增加 `initiatorRole` 字段，并保留旧构造器兼容既有测试/调用。
- Modify: `frontend/src/views/disputes/EvidenceRoomView.vue`
  - 当前 actor 为发起争议方且我的原件匣中无正式提交证据时，点击“举证完成”直接展示弹窗/错误提示，不调用后端完成接口。
- Test: `java-api-service/src/test/java/com/example/dispute/evidence/EvidenceCompletionServiceTest.java`
  - 覆盖发起争议方未正式提交证据时不能手动完成举证。
- Test: `java-api-service/src/test/java/com/example/dispute/evidence/EvidenceRoomIntegrationTest.java`
  - 覆盖时效到期也不能绕过准入；无发起方证据时不创建庭审房间。
- Test: `frontend/src/views/disputes/EvidenceRoomView.test.js`
  - 覆盖前端本地阻断和中文错误提示。
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingRoundService.java`
  - 修正乱码 summary/fallback 文案。
  - 在最终轮封存后触发裁决草案/审核流转所需状态。
- Modify/Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingStatusView.java`
  - 返回前端需要的 `hearing_phase`、`next_step_hint`、`can_complete_hearing`、`latest_draft_id`、`review_gate_ready`。
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/api/HearingCollaborationController.java`
  - `GET /hearing` 增加 `status`。
  - 增加 `POST /hearing/complete` 或等价完成动作；若草案未准备好，不跳 outcome。
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingOutcomeOrchestrationService.java`
  - 保持幂等：已有 draft/remedy/review 不重复创建。
- Modify/Create: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceDossierRevisionService.java`
  - 第二轮证据书记官复核后，根据补证、双方证据解释和旧矩阵生成新的 active evidence_dossier 版本。
- Modify: `java-api-service/src/main/java/com/example/dispute/infrastructure/persistence/entity/EvidenceDossierEntity.java`
  - 增加 baseline/supersedes/active/revision metadata，或在兼容现有表结构时先把这些字段写入 `matrix_summary_json`。
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/AgentA2AMessageService.java`
  - 保存陪审团给法官的 A2A note/report，并为法官调用提供按 case/round/to_agent 的读取接口。
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtOrchestrator.java`
  - 法官调用前不只读取 bootstrap snapshot，还要合并最新 active evidence_dossier 和 A2A note/report。
- Modify: `python-agent-service/app/schemas/final_agents.py`
  - 扩展 `HearingRoundTurnRequest`/`HearingRoundTurnResult` 支持 `evidence_dossier_ref`、`party_alignment`、`a2a_notes`。
- Modify: `python-agent-service/app/agents/prompts/presiding_judge/hearing_round_turn.md`
  - 固定第 3 轮方案确认语义：确认法官拟处理方向，非独立和解方案。
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationIntegrationTest.java`
  - 覆盖三轮封存后的完成 readiness、未完成时不能进入 outcome 语义、summary 中文。
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingEvidenceDossierRevisionTest.java`
  - 覆盖第 2 轮复核后 active evidence_dossier 从 v1 更新到 v2，法官调用读取 v2。
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/AgentA2AMessageServiceTest.java`
  - 覆盖 A2A 消息入库、版本引用、按接收 agent 拉取、当事人不可直接创建。
- Test: `python-agent-service/tests/agents/test_presiding_judge_round_turn.py`
  - 覆盖第 3 轮方案确认、双方一致标签、异议信息、最新 evidence_dossier_ref 注入。
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingOutcomeOrchestrationServiceIntegrationTest.java`
  - 覆盖 completed hearing + draft → review gate 幂等。
- Modify: `frontend/src/api/hearing.js`
  - 新增 `complete` API。
- Modify: `frontend/src/views/disputes/HearingCourtView.vue`
  - 使用后端 `status` 决定状态台、庭审完成按钮、卷轴内容。
  - 未 ready 时不直接跳 outcome。
- Test: `frontend/src/views/disputes/HearingCourtView.test.js`
  - 覆盖后端状态驱动、完成按钮语义、卷轴展示。

---

## Task 0: 开庭前发起方证据准入门槛

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceCompletionService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/infrastructure/persistence/repository/EvidenceItemRepository.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceCatalogService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/RoleScopedEvidenceView.java`
- Modify: `frontend/src/views/disputes/EvidenceRoomView.vue`
- Test: `java-api-service/src/test/java/com/example/dispute/evidence/EvidenceCompletionServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/evidence/EvidenceRoomIntegrationTest.java`
- Test: `frontend/src/views/disputes/EvidenceRoomView.test.js`

- [x] **Step 1: Write failing backend unit test for manual completion**

Add a test in `EvidenceCompletionServiceTest`:

```java
@Test
void initiatorCannotCompleteEvidenceWithoutSubmittedEvidence() {
    when(evidenceRepository.countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull(
                    dispute.getId(), "USER", EvidenceSubmissionStatus.SUBMITTED))
            .thenReturn(0L);

    assertThatThrownBy(
                    () ->
                            service.complete(
                                    dispute.getId(),
                                    new AuthenticatedActor("user-local", ActorRole.USER),
                                    "user-complete-without-evidence"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("发起争议方需先正式提交至少 1 份相关证据");
}
```

- [x] **Step 2: Write failing backend integration test for deadline expiry bypass**

Add a test in `EvidenceRoomIntegrationTest`:

```java
@Test
void deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing() {
    seedEvidenceCase("CASE_NO_SUBMISSIONS");

    assertThatThrownBy(() -> completionService.expire("CASE_NO_SUBMISSIONS"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("发起争议方需先正式提交至少 1 份相关证据");

    FulfillmentCaseEntity dispute =
            caseRepository.findById("CASE_NO_SUBMISSIONS").orElseThrow();
    assertThat(dispute.getCaseStatus()).isEqualTo(CaseStatus.EVIDENCE_OPEN);
    assertThat(dispute.getCurrentRoom()).isEqualTo("EVIDENCE");
    assertThat(roomRepository.findByCaseIdAndRoomType("CASE_NO_SUBMISSIONS", RoomType.HEARING))
            .isEmpty();
}
```

- [x] **Step 3: Verify RED**

Run:

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=EvidenceRoomIntegrationTest#deadlineExpiryWithoutInitiatorEvidenceDoesNotOpenHearing" test
```

Expected before implementation: FAIL because `expire(caseId)` still opens hearing without checking initiator evidence.

- [x] **Step 4: Implement backend admission guard**

Add `EvidenceItemRepository` method:

```java
long countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull(
        String caseId, String submittedByRole, EvidenceSubmissionStatus submissionStatus);
```

Inject it into `EvidenceCompletionService`, then call this guard from both `complete(...)` and `expire(...)`:

```java
private void assertInitiatorHasSubmittedEvidence(FulfillmentCaseEntity dispute) {
    ActorRole initiatorRole = dispute.getInitiatorRole();
    long submitted =
            evidenceRepository
                    .countByCaseIdAndSubmittedByRoleAndSubmissionStatusAndDeletedAtIsNull(
                            dispute.getId(),
                            initiatorRole.name(),
                            EvidenceSubmissionStatus.SUBMITTED);
    if (submitted > 0) {
        return;
    }
    throw new BadRequestException(
            "发起争议方需先正式提交至少 1 份相关证据，当前证据栏为空，暂不能进入下一步。",
            Map.of(
                    "case_id", dispute.getId(),
                    "initiator_role", initiatorRole.name(),
                    "required_submission_status", EvidenceSubmissionStatus.SUBMITTED.name()));
}
```

- [x] **Step 5: Implement frontend local prompt**

Expose `initiatorRole` from the evidence catalog and add local blocking in `EvidenceRoomView.vue`:

```js
const initiatorRole = computed(
  () => catalog.value?.initiator_role || catalog.value?.initiatorRole || "USER",
);
const currentActorIsInitiator = computed(() => role.value === initiatorRole.value);
const canCompleteEvidenceLocally = computed(
  () => !currentActorIsInitiator.value || submittedItems.value.length > 0,
);

async function completeEvidence() {
  if (!canCompleteEvidenceLocally.value) {
    error.value = "发起争议方需先正式提交至少 1 份相关证据，当前证据栏为空，暂不能进入下一步。";
    agentState.value = "ERROR";
    return;
  }
  // existing backend call follows
}
```

- [x] **Step 6: Verify GREEN**

Run:

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=EvidenceCompletionServiceTest,EvidenceRoomIntegrationTest,EvidenceCatalogServiceTest,EvidenceSubmissionServiceTest" test
cd ..\frontend
pnpm exec vitest run src/views/disputes/EvidenceRoomView.test.js
```

Expected after implementation:

```text
Java: Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
Frontend: 28 tests passed
```

## Task 1: 后端庭审状态视图合同

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingStatusView.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingRoundService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/api/HearingCollaborationController.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationIntegrationTest.java`

- [x] **Step 1: Write failing integration test**

Add a test that seeds a hearing with one open round and asserts `roundService.status(caseId, user)` returns:

```java
assertThat(status.hearingPhase()).isEqualTo("ROUND_OPEN");
assertThat(status.canCompleteHearing()).isFalse();
assertThat(status.nextStepHint()).contains("完成本轮陈述");
```

- [x] **Step 2: Verify RED**

Run:

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=HearingCollaborationIntegrationTest#hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness" test
```

Expected: compile failure because `status` / `HearingStatusView` does not exist.

- [x] **Step 3: Implement minimal status view**

Create `HearingStatusView` as a Java record and add `HearingRoundService.status(...)`.

- [x] **Step 4: Expose via `GET /hearing`**

Add `"status", roundService.status(caseId, actor)` to the hearing endpoint response.

- [x] **Step 5: Verify GREEN**

Run the same Maven test and confirm it passes.

---

## Task 2: 庭审完成按钮后端语义

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/api/HearingCollaborationController.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingOutcomeOrchestrationService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingOutcomeOrchestrationServiceIntegrationTest.java`

- [x] **Step 1: Write failing test**

Add a test that calls completion before final round/draft readiness and expects a business error or `can_complete_hearing=false`. Add a second test for completed hearing + existing draft that returns `review_gate_ready=true`.

- [x] **Step 2: Verify RED**

Run targeted tests.

- [x] **Step 3: Implement idempotent completion action**

Completion must not create a final outcome page by itself. It either returns current status or orchestrates completed hearing into review gate only when a draft exists.

- [x] **Step 4: Verify GREEN**

Run targeted tests.

---

## Task 3: 乱码文案与可读 summary 修复

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingRoundService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtOrchestrator.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationIntegrationTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCourtOrchestratorTest.java`

- [x] **Step 1: Write failing tests**

Assert submitted/timeout/fallback summaries contain readable Chinese:

```java
assertThat(round.getSummaryJson()).contains("双方本轮陈述已提交并封存");
assertThat(round.getSummaryJson()).doesNotContain("鍙");
```

- [x] **Step 2: Verify RED**

Run targeted tests and confirm failure on current mojibake.

- [x] **Step 3: Replace hard-coded mojibake strings**

Use objective third-person Chinese and keep JSON keys stable.

- [x] **Step 4: Verify GREEN**

Run targeted tests.

---

## Task 4: 前端状态驱动与庭审完成按钮

**Files:**
- Modify: `frontend/src/api/hearing.js`
- Modify: `frontend/src/views/disputes/HearingCourtView.vue`
- Test: `frontend/src/views/disputes/HearingCourtView.test.js`

- [x] **Step 1: Write failing tests**

Assert:

```js
expect(wrapper.get("[data-complete-hearing]").element.disabled).toBe(true);
expect(wrapper.text()).toContain("等待裁决草案");
```

When `initialHearing.status.can_complete_hearing === true`, clicking calls the backend completion action and routes to outcome only after success.

- [x] **Step 2: Verify RED**

Run:

```powershell
cd frontend
pnpm exec vitest run src/views/disputes/HearingCourtView.test.js
```

- [x] **Step 3: Implement minimal frontend state usage**

Read `hearing.status`; disable/enable completion button; add `hearingApi.complete`.

- [x] **Step 4: Verify GREEN**

Run the same Vitest command.

---

## Task 5: 庭审卷轴增强

**Files:**
- Modify: `frontend/src/views/disputes/HearingCourtView.vue`
- Test: `frontend/src/views/disputes/HearingCourtView.test.js`

- [x] **Step 1: Write failing test**

Assert ledger shows round number, round status, submitted roles, summary text, and a “正在形成中” state for an open round.

- [x] **Step 2: Verify RED**

Run Vitest targeted file.

- [x] **Step 3: Implement ledger display**

Use existing rounds/messages; do not fabricate mock messages.

- [x] **Step 4: Verify GREEN**

Run Vitest targeted file.

---

## Task 6: 第三轮方案确认合同

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingStatusView.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingRoundService.java`
- Modify: `frontend/src/views/disputes/HearingCourtView.vue`
- Modify: `python-agent-service/app/agents/prompts/presiding_judge/hearing_round_turn.md`
- Modify: `python-agent-service/app/agents/presiding_judge/round_workflow.py`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationIntegrationTest.java`
- Test: `frontend/src/views/disputes/HearingCourtView.test.js`
- Test: `python-agent-service/tests/agents/test_presiding_judge_round_turn.py`

- [x] **Step 1: Write failing tests**

Java:

```java
assertThat(status.roundNo()).isEqualTo(3);
assertThat(status.roundStage()).isEqualTo("REMEDY_CONFIRMATION");
assertThat(status.phaseLabel()).contains("方案确认");
assertThat(status.nextStepHint()).contains("确认或说明异议");
```

Frontend:

```js
expect(progressItems[2].get(".round-progress-board__label").text()).toBe("方案确认");
expect(wrapper.text()).not.toContain("提出一致方案");
expect(wrapper.text()).toContain("确认或说明异议");
```

Python:

```python
assert "方案确认" in runner.last_prompt
assert "双方一致" in runner.last_prompt
assert "不是和解协议" in runner.last_prompt
```

- [x] **Step 2: Verify RED**

Run:

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=HearingCollaborationIntegrationTest#hearingStatusViewTracksOpenWaitingAndFinalDraftReadiness" test
cd ..\frontend
pnpm exec vitest run src/views/disputes/HearingCourtView.test.js
cd ..\python-agent-service
python -m pytest tests/agents/test_presiding_judge_round_turn.py -q
```

Expected: failures because round 3 still uses old wording/contract or lacks `roundStage`.

- [x] **Step 3: Implement minimal contract**

Use these stable values:

```text
round_no=1 -> FACT_STATEMENT / 事实陈述
round_no=2 -> EVIDENCE_EXPLANATION / 证据解释
round_no=3 -> REMEDY_CONFIRMATION / 方案确认
```

Third round wording must say: 法官提出非最终拟处理方向，双方确认或说明异议。It must not expose a “提出一致方案” button and must not claim a final result is effective.

- [x] **Step 4: Verify GREEN**

Run the same three commands and confirm the targeted tests pass.

---

## Task 7: 第二轮证据矩阵版本化更新

**Files:**
- Modify/Create: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceDossierRevisionService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/infrastructure/persistence/entity/EvidenceDossierEntity.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingRoundService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtOrchestrator.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingEvidenceDossierRevisionTest.java`

- [x] **Step 1: Write failing test**

Create a case with bootstrap evidence dossier v1, submit both parties’ round 2 evidence explanations, close round 2, and assert:

```java
EvidenceDossierEntity active = evidenceDossierRepository
        .findTopByCaseIdOrderByDossierVersionDesc(caseId)
        .orElseThrow();

assertThat(active.getDossierVersion()).isEqualTo(2);
assertThat(active.getMatrixSummaryJson()).contains("revision_reason");
assertThat(active.getMatrixSummaryJson()).contains("updated_after_round");
assertThat(active.getMatrixSummaryJson()).contains("fact_evidence_matrix");
```

- [x] **Step 2: Verify RED**

Run:

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=HearingEvidenceDossierRevisionTest" test
```

Expected: compile/test failure because the revision service or metadata does not exist.

- [x] **Step 3: Implement minimal revision behavior**

When round 2 seals:

1. Load latest evidence dossier as baseline/previous active version.
2. Merge round 2 party submissions and supplemental evidence references into matrix metadata.
3. Create or rebuild a new active dossier version.
4. Record `revision_reason`, `updated_after_round=2`, `supersedes_version`, `active_version`.
5. Write a ledger/event payload named `EVIDENCE_DOSSIER_REVISED`.

If generation fails, write an explicit failure record and do not let final draft generation continue on stale v1.

- [x] **Step 4: Verify GREEN**

Run the same Maven test and confirm it passes.

---

## Task 8: 法官调用重新拼接最新 evidence_dossier

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtOrchestrator.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtBootstrapService.java`
- Modify: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCourtOrchestratorTest.java`
- Modify: `java-api-service/src/test/java/com/example/dispute/hearing/RestClientHearingCourtAgentClientTest.java`

- [x] **Step 1: Write failing test**

Seed bootstrap snapshot with `evidence_dossier_version=1`, then seed active evidence dossier v2 before opening/closing round 3. Assert the Python agent request contains:

```java
.andExpect(jsonPath("$.courtroom_context.evidence_dossier_ref.baseline_version").value(1))
.andExpect(jsonPath("$.courtroom_context.evidence_dossier_ref.active_version").value(2))
.andExpect(jsonPath("$.courtroom_context.evidence_dossier.dossier_version").value(2))
```

- [x] **Step 2: Verify RED**

Run:

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=RestClientHearingCourtAgentClientTest,HearingCourtOrchestratorTest" test
```

Expected: failure because current orchestration reads the frozen bootstrap snapshot only.

- [x] **Step 3: Implement active context composition**

Keep bootstrap snapshot as the baseline, but before each judge call:

1. Read bootstrap `source_versions.evidence_dossier_version` as `baseline_version`.
2. Read latest active evidence dossier as `active_version`.
3. Replace `courtroom_context.evidence_dossier` with active matrix.
4. Add `courtroom_context.evidence_dossier_ref`.
5. Add the used evidence version to agent run/lifecycle event metadata.

- [x] **Step 4: Verify GREEN**

Run the same Java tests and confirm they pass.

---

## Task 9: 陪审团 A2A 通信链路

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/AgentA2AMessageService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/infrastructure/persistence/entity/AgentA2AMessageEntity.java`
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/infrastructure/persistence/repository/AgentA2AMessageRepository.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtOrchestrator.java`
- Modify: `python-agent-service/app/schemas/final_agents.py`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/AgentA2AMessageServiceTest.java`
- Test: `python-agent-service/tests/agents/test_presiding_judge_round_turn.py`

- [x] **Step 1: Write failing tests**

Java:

```java
service.record(new AgentA2ACommand(
        caseId,
        2,
        "JURY_PANEL",
        "PRESIDING_JUDGE",
        "JURY_SILENT_NOTE",
        Map.of("evidence_dossier_version", 2),
        Map.of("judge_attention", List.of("签收人身份仍需关注")),
        "SYSTEM_AUDIT_ONLY"));

assertThat(service.findForJudge(caseId, 3))
        .anySatisfy(note -> assertThat(note.messageType()).isEqualTo("JURY_SILENT_NOTE"));
```

Python:

```python
payload = request(courtroom_context={
    "jury_a2a_notes": [
        {"message_type": "JURY_SILENT_NOTE", "payload": {"judge_attention": ["签收人身份仍需关注"]}}
    ]
})
result = HearingRoundTurnWorkflow(model_runner=runner).run(payload)
assert "签收人身份" in runner.last_prompt
```

- [x] **Step 2: Verify RED**

Run:

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=AgentA2AMessageServiceTest" test
cd ..\python-agent-service
python -m pytest tests/agents/test_presiding_judge_round_turn.py -q
```

Expected: failure because A2A service/schema is missing.

- [x] **Step 3: Implement A2A envelope**

Persist:

```json
{
  "a2a_message_id": "A2A_xxx",
  "case_id": "CASE_xxx",
  "round_no": 2,
  "from_agent": "JURY_PANEL",
  "to_agent": "PRESIDING_JUDGE",
  "message_type": "JURY_SILENT_NOTE",
  "input_refs": {},
  "payload": {},
  "visibility": "SYSTEM_AUDIT_ONLY"
}
```

Rules:

1. USER/MERCHANT cannot create A2A messages.
2. Java injects A2A notes/reports into `courtroom_context.jury_a2a_notes`.
3. Jury does not directly create adjudication draft.
4. Frontend may show a Chinese summary card, but never raw A2A JSON.

- [x] **Step 4: Verify GREEN**

Run the same Java and Python tests and confirm they pass.

---

## Task 10: End-to-end verification

**Files:**
- No planned source file unless verification exposes defects.

- [x] **Step 1: Backend targeted suite**

Run:

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=HearingCourtBootstrapServiceTest,RoomMessageAndEventServiceTest,HearingCollaborationIntegrationTest,HearingOutcomeOrchestrationServiceIntegrationTest,EvidenceSubmissionServiceTest,EvidenceCatalogServiceTest" test
```

- [x] **Step 2: Frontend targeted suite**

Run:

```powershell
cd frontend
pnpm exec vitest run src/views/disputes/HearingCourtView.test.js src/utils/displayText.test.js
```

- [x] **Step 3: Browser/API smoke**

Create or reuse a hearing case, open `/disputes/{caseId}/hearing`, verify:

1. opening messages are real room messages;
2. current actor submits statement and transcript updates;
3. evidence supplement writes an evidence reference, but does not count as round statement;
4. completion button follows backend `can_complete_hearing`;
5. right-side ledger opens and shows traceable records;
6. round 3 displays “方案确认” and no “提出一致方案” button;
7. after round 2, evidence dossier version is updated and judge round 3/final draft uses the active version;
8. jury A2A note/report is persisted and injected into the judge context;
9. if both parties confirm the judge proposed remedy, the draft carries “双方一致”; if either party objects, the draft carries disagreement summary and reviewer attention.
10. if the dispute initiator has no formally submitted evidence, evidence completion and deadline expiry both block hearing admission and show a Chinese business error.

---

## 已执行验收记录

截至本计划更新时，Task 0 已按 TDD 路径执行，并完成以下验证：

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=EvidenceCompletionServiceTest,EvidenceRoomIntegrationTest,EvidenceCatalogServiceTest,EvidenceSubmissionServiceTest" test
```

Result:

```text
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

```powershell
cd frontend
pnpm exec vitest run src/views/disputes/EvidenceRoomView.test.js
```

Result:

```text
Test Files 1 passed
Tests 28 passed
```

为确认新增准入门槛没有破坏后续庭审链路，同步执行：

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=HearingCourtBootstrapServiceTest,HearingCollaborationIntegrationTest,HearingCourtOrchestratorTest,AgentA2AMessageServiceTest,RestClientHearingCourtAgentClientTest,HearingOutcomeOrchestrationServiceIntegrationTest,RoomMessageAndEventServiceTest" test
```

Result:

```text
Tests run: 47, Failures: 0, Errors: 0, Skipped: 0
```

```powershell
cd frontend
pnpm exec vitest run src/views/disputes/HearingCourtView.test.js src/views/disputes/OutcomeView.test.js src/utils/displayText.test.js
```

Result:

```text
Test Files 3 passed
Tests 32 passed
```

```powershell
cd python-agent-service
C:\Users\Jupiter\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -m pytest tests/agents/test_presiding_judge_round_turn.py tests/test_api.py tests/test_prompts.py -q
```

Result:

```text
12 passed, 1 warning
```


### 2026-07-09 继续执行记录

本轮已将 Task 1–10 的计划步骤按实际实现状态回填为 `[x]`。其中 Task 7 的证据矩阵版本化验证落在 `HearingCollaborationIntegrationTest#secondRoundEvidenceExplanationRevisesActiveEvidenceDossierVersion` 中，而不是单独新建 `HearingEvidenceDossierRevisionTest` 文件；语义覆盖为：第二轮证据解释封存后生成 active evidence dossier v2，并由后续法官上下文读取最新 active version。

Fresh verification commands:

```powershell
cd frontend
pnpm exec vitest run src/views/disputes/HearingCourtView.test.js src/utils/displayText.test.js
```

Result:

```text
Test Files 2 passed
Tests 28 passed
```

```powershell
cd python-agent-service
C:\Users\Jupiter\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -m pytest tests/agents/test_presiding_judge_round_turn.py tests/test_api.py tests/test_prompts.py -q
```

Result:

```text
12 passed, 1 warning
```

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=HearingCourtBootstrapServiceTest,RoomMessageAndEventServiceTest,HearingCollaborationIntegrationTest,HearingOutcomeOrchestrationServiceIntegrationTest,EvidenceSubmissionServiceTest,EvidenceCatalogServiceTest" test
```

Result:

```text
Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Browser/API smoke:

- `http://127.0.0.1:5173/disputes` returned HTTP 200.
- `http://127.0.0.1:8080/actuator/health` returned UP.
- Opened `http://127.0.0.1:5173/disputes/CASE_a8c69ab237244085961166613cbdaf7d/hearing` in the in-app browser.
- Verified page renders without “127.0.0.1 拒绝建立连接”.
- Verified visible hearing content contains `AI 小法庭 · 履约争端庭审`, `事实陈述`, `证据解释`, `方案确认`, `案情接待官`, `证据书记官`, `主审法官`, user/merchant evidence rails, and `庭审完成`.
- Verified normal hearing UI does **not** show `提出一致方案`.
- Opened `查看庭审卷轴`; verified it shows `第 1 轮`、`第 2 轮`、`第 3 轮` and sealed/completed records from real rounds/messages.

Implementation note:

- The historical settlement/proposal API still exists in code for older flows, but this phase explicitly does not expose or call “提出一致方案” from the normal hearing page. The independent consistent-plan/settlement design remains a later product lane.

### 2026-07-09 追加优化记录

根据最新产品语义，完成以下前端主线补强：

- 正常庭审主线继续保留“一致方案/和解”遗留代码与 API，但庭审页不展示入口、不触发提交、不依赖其推进轮次。
- 当前轮封存与三轮结束输入台改为锁定状态：封存态不渲染 textarea，不再呈现可编辑输入框假象；三轮结束展示“庭审已封存，等待裁决草案”。
- 右侧完成入口文案固定为“查看裁决草案”；未 ready 时保持禁用和“等待裁决草案”。
- 正式 `JURY_REVIEW_REPORT` 以“评审团复核报告”卡片展示在庭审记录中；`SYSTEM_AUDIT_ONLY` 静默 A2A note 不进入当事人庭审界面。
- 庭审卷轴增加补证、证据矩阵版本更新、评审团复核报告三类可追溯条目；前端只展示中文业务摘要，不暴露 `A2A` 原始字段、`evidence_dossier_version` 等内部通信数据。
- 草案/outcome 房间增加“解释员复盘”卡片；解释员在草案阶段复盘庭审、解释方案依据和后续确认关注点，不出现在庭审页。

Fresh verification commands:

```powershell
cd frontend
pnpm exec vitest run src/views/disputes/HearingCourtView.test.js src/views/disputes/OutcomeView.test.js src/utils/displayText.test.js
```

Result:

```text
Test Files 3 passed
Tests 35 passed
```

```powershell
cd frontend
pnpm run build
```

Result:

```text
✓ built
```

Browser smoke:

- Opened `http://localhost:5173/disputes/CASE_a8c69ab237244085961166613cbdaf7d/hearing`.
- Current case is still in an open round, so input dock correctly shows textarea + `提交陈述`.
- Confirmed the page does not expose `提出一致方案` / `和解意向`.
- Confirmed the page does not expose `A2A_INTERNAL`, `SYSTEM_AUDIT_ONLY`, `evidence_dossier_version`, or `JURY_REVIEW_REPORT`.
- Confirmed `查看庭审卷轴` remains available and草案入口在未 ready 时显示 `等待裁决草案`。

### 2026-07-09 Java ↔ Python live final-draft smoke

本轮继续按“先定位边界，再验证闭环”的方式检查最终裁决草案链路。

Root-cause evidence:

- 从 `hearing_stage_record` 取最近一次 `C6_ADJUDICATION_DRAFT` 的真实 `input_json`，直连 live Python `/internal/agents/legacy/hearing/analyze`。
- 初始 live Python 返回 422，错误为 `hearing_context.sealed_rounds` 与 `hearing_context.courtroom_context` 被旧 schema 判定为 extra forbidden。
- 当前工作区 `python-agent-service/app/schemas/models.py` 与 `python-agent-service/tests/test_api.py` 已支持这两个字段；重启 Python agent 后，同一份真实 payload 返回 HTTP 200，并返回 `adjudication_draft`。

Fresh Python contract verification:

```powershell
cd python-agent-service
C:\Users\Jupiter\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -m pytest tests/test_api.py tests/agents/test_presiding_judge_round_turn.py tests/test_prompts.py -q
```

Result:

```text
12 passed, 1 warning
```

Live HTTP smoke case:

- Created fresh case: `CASE_83a484ce3c46409ba2f87f997a8ffa56`
- Uploaded and formally submitted initiator evidence with `source_type=USER_UPLOAD`
- Completed evidence room for both parties
- Bootstrapped hearing room
- Completed three rounds:
  - Round 1 -> `EVIDENCE_EXPLANATION / OPEN / ROUND_OPEN`
  - Round 2 -> `REMEDY_CONFIRMATION / OPEN / ROUND_OPEN`
  - Round 3 -> `REMEDY_CONFIRMATION / FORCED_CLOSED / JUDGE_DRAFTING`
- Called `POST /api/disputes/CASE_83a484ce3c46409ba2f87f997a8ffa56/hearing/complete`
- Generated draft: `DRAFT_2fbfab2ed0d84560b9a839819785898c`
- Read outcome: `GET /api/disputes/CASE_83a484ce3c46409ba2f87f997a8ffa56/outcome`
- Verified `FALLBACK_PRESENT=False`; draft/reviewer attention did not contain `兜底` or `422`。

Follow-up browser finding and fix:

- Outcome page initially rendered structured draft objects with raw field names / enum values such as `issue_id`, `policy_basis`, `supported_by`, `NEEDS_HUMAN_REVIEW` and raw evidence identifiers inside party-visible text.
- Added frontend regression coverage in `frontend/src/views/disputes/OutcomeView.test.js` to assert party-facing draft pages do not expose raw draft field names, internal enum values, or internal evidence IDs.
- Updated `frontend/src/views/disputes/OutcomeView.vue` draft rendering so:
  - internal locator keys such as `issue_id` / `evidence_id` are hidden;
  - business fields such as `suggested_finding` / `neutral_analysis` are rendered with Chinese labels;
  - embedded enum tokens such as `NEEDS_HUMAN_REVIEW` are localized to `待人工复核`;
  - embedded internal identifiers such as `EVIDENCE_xxx` are replaced by `相关材料`;
  - A2A / evidence dossier / courtroom context internals remain hidden from party UI.

Fresh frontend verification after this fix:

```powershell
cd frontend
pnpm exec vitest run src/views/disputes/HearingCourtView.test.js src/views/disputes/OutcomeView.test.js src/utils/displayText.test.js
```

Result:

```text
Test Files 3 passed
Tests 36 passed
```

```powershell
cd frontend
pnpm run build
```

Result:

```text
✓ built
```

Browser verification:

- Refreshed `http://localhost:5173/disputes/CASE_83a484ce3c46409ba2f87f997a8ffa56/outcome`.
- Verified draft page contains `AI 裁决草案` and `解释员复盘`.
- Verified `hasFallback=false`.
- Verified party-visible leak scan returned an empty list for `issue_id`, `policy_basis`, `evidence_basis`, `suggested_finding`, `supported_by`, `contradicted_by`, `missing_evidence`, `neutral_analysis`, `NEEDS_HUMAN_REVIEW`, `SIGNED_NOT_RECEIVED`, `SYSTEM_AUDIT_ONLY`, `evidence_dossier_version`, `JURY_REVIEW_REPORT`, `A2A_INTERNAL`, `sealed_rounds`, `courtroom_context`。

### 2026-07-10 Final round recovery / live boundary evidence

后端恢复修复已提交：

- `e09f9e0 fix: recover final hearing rounds from initial cursor`

Fresh Java verification:

```powershell
cd java-api-service
.\mvnw.cmd "-Dtest=HearingFinalRoundRecoveryServiceTest,HearingPersistenceIntegrationTest#finalRoundRecoveryQuerySupportsInitialNullCursorOnPostgresql" test
```

Result:

```text
6 tests, 0 failures, BUILD SUCCESS
```

Live E2E identifiers:

- case: `CASE_671ac52627874756b548d2fb501fab65`
- draft: `DRAFT_8abe897d74104073bc123c96ad35d7c0`
- review task: `REVIEW_1e8e70da3af84f3d8ab89093db81c290`
- packet: `PACKET_ba72a5f1dda3438695d6717d23917146`
- remedy: `REMEDY_59a2a3b17678492590f848d399aec5b6`

Boundary checks:

- Fixed demo actors are `user-local`, `merchant-local`, `reviewer-local`; simulate import remains `count=1` single-case only.
- `reviewer-local` can `GET /api/reviews?status=PENDING`; `user-local` and `merchant-local` receive 403 for that reviewer list.
- Outcome does not leak reviewer controls to party views, and reviewer outcome view does not leak party-only controls.
- If initiator evidence is empty, evidence completion returns 400 and hearing admission is blocked.
- Third-round natural-language feedback is kept as jury review focus only; it is not directly adopted as the adjudication result. Review and evidence-matrix updates remain A2A context inputs.

## Self-review

- Spec coverage: all ten points and the added “开庭准入硬门槛” have an implementation or follow-up lane.
- Placeholder scan: no task uses “TBD/TODO”; later deepening work is explicitly outside first-stage acceptance.
- Type consistency: first-stage status fields use snake_case over HTTP and camelCase in Java records via existing Jackson config.
- Admission consistency: manual `complete` and automatic `expire` both use the same backend guard; frontend local blocking is only UX optimization and does not replace backend enforcement.

