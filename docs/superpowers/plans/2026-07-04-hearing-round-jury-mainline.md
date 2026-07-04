# Hearing Round Jury Mainline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通主体小法庭链路：双方本轮提交、5 分钟轮次时效、AI 法官生成轮次判断、最终草案前评审团门禁，并把数字人管理中心作为后期配置模块预留。

**Architecture:** Java/Temporal 继续作为权威状态机，前端只提交意图和展示状态。评审团策略先落成可配置领域模型，默认 `FINAL_ONLY`，后续数字人管理中心接真实后端时复用同一配置接口。

**Tech Stack:** Spring Boot, JPA, Temporal workflow tests, Vue 3, Vue Router, Vitest, Browser E2E.

---

### Task 1: Frontend Agent Console Placeholder

**Files:**
- Create: `frontend/src/views/agents/AgentConsoleView.vue`
- Create: `frontend/src/views/agents/AgentConsoleView.test.js`
- Modify: `frontend/src/router/index.js`
- Modify: `frontend/src/router/router.test.js`
- Modify: `frontend/src/App.vue`

- [x] **Step 1: Write failing route and page tests**

Run: `npx vitest run src/router/router.test.js src/views/agents/AgentConsoleView.test.js --reporter=verbose`

Expected before implementation: `/agents` route missing and component import missing.

- [x] **Step 2: Implement placeholder page and reviewer-only route**

Add a unified dispute-park visual shell for `/agents`, with five digital human roles and jury strategy cards.

- [x] **Step 3: Verify tests pass**

Run: `npx vitest run src/router/router.test.js src/views/agents/AgentConsoleView.test.js --reporter=verbose`

Expected: all tests pass.

### Task 2: Hearing Round Submission Domain

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/**`
- Modify: `java-api-service/src/main/java/com/example/dispute/workflow/temporal/DisputeHearingWorkflowImpl.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationIntegrationTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/workflow/DisputeHearingWorkflowTest.java`

- [ ] **Step 1: Write failing backend tests**

Cover user submit, merchant submit, both-submitted transition, and timeout auto-submit semantics.

- [ ] **Step 2: Implement authoritative round submission state**

Persist party submission state in Java and signal the hearing workflow only when both parties submitted or round timer expires.

- [ ] **Step 3: Verify backend tests pass**

Run targeted Maven tests for hearing collaboration and hearing workflow.

### Task 3: Jury Strategy Configuration

**Files:**
- Modify/Create: `java-api-service/src/main/java/com/example/dispute/deliberation/**`
- Modify: `java-api-service/src/main/java/com/example/dispute/workflow/application/WorkflowApplicationService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/workflow/temporal/FulfillmentDisputeWorkflowImpl.java`
- Test: `java-api-service/src/test/java/com/example/dispute/workflow/**`

- [ ] **Step 1: Write failing strategy tests**

Default strategy must be `FINAL_ONLY`, threshold `80`, max judge regeneration `2`; `THREE_ROUND` remains configurable but not default.

- [ ] **Step 2: Implement strategy model**

Add a deterministic Java strategy object consumed by workflows. Do not make frontend the source of truth.

- [ ] **Step 3: Verify tests pass**

Run targeted workflow and strategy tests.

### Task 4: Frontend Hearing Round Controls

**Files:**
- Modify: `frontend/src/views/disputes/HearingCourtView.vue`
- Modify: `frontend/src/stores/hearing.js`
- Modify: `frontend/src/api/hearing.js`
- Test: `frontend/src/views/disputes/HearingCourtView.test.js`

- [ ] **Step 1: Replace wrong platform-host submit test**

Parties, not platform host, own the per-round submit buttons.

- [ ] **Step 2: Add user/merchant round submit controls and timeout display**

Show the active party’s submit button, waiting state for the counterparty, and 5-minute round timer.

- [ ] **Step 3: Verify frontend tests pass**

Run targeted Vitest files.

### Task 5: Browser E2E Mainline

**Files:**
- Modify tests only as needed under `tests/e2e/`

- [ ] **Step 1: Start local hot-dev topology**

Spring Boot and Python Agent run locally; MySQL/Redis/Milvus/MinIO remain Docker Compose.

- [ ] **Step 2: Browser-drive user and merchant through main flow**

Use the UI, not direct API calls, to enter evidence, submit hearing rounds, reach review, and view outcome.

- [ ] **Step 3: Fix discovered blockers with TDD**

Every blocker gets a failing test first, then implementation.
