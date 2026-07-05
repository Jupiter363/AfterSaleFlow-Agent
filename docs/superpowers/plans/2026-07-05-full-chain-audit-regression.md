# Full Chain Audit and Regression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute the formal full-chain audit, repair, and regression loop for the AI Native fulfillment dispute system, using role switching instead of login.

**Architecture:** The audit is evidence-driven: build a feature matrix from docs/routes/controllers/migrations, verify runtime infrastructure, scan the UI through the browser as USER/MERCHANT/PLATFORM_REVIEWER, record every issue, fix with regression tests, and repeat until no blocking formal-flow issues remain. Login is intentionally out of scope for this run; the frontend identity dropdown is the authoritative role simulation surface.

**Tech Stack:** Vue 3/Vite/Element Plus frontend, Spring Boot Java API, PostgreSQL/Redis/Temporal/MinIO/Elasticsearch Docker dependencies, Python FastAPI Agent service, LiteLLM/Langfuse model gateway and tracing.

---

### Task 1: Establish Audit Deliverables

**Files:**
- Create: `docs/acceptance/full-chain-audit/全量功能矩阵.md`
- Create: `docs/acceptance/full-chain-audit/全链路问题清单.md`
- Create: `docs/acceptance/full-chain-audit/Agent全链路排查与修复报告.md`
- Create: `docs/acceptance/full-chain-audit/多角色端到端验收报告.md`
- Create: `docs/acceptance/full-chain-audit/全量回归测试报告.md`
- Create: `docs/acceptance/full-chain-audit/修复变更记录.md`
- Create: `docs/acceptance/full-chain-audit/最终验收清单.md`

- [ ] **Step 1: Create the seven required documents**

Run: `Test-Path docs/acceptance/full-chain-audit/全量功能矩阵.md`

Expected: `True` after creation.

- [ ] **Step 2: Seed the matrix from frontend routes and backend controllers**

Run: `rg "@(Get|Post|Put|Delete|Patch)Mapping|@RequestMapping" java-api-service/src/main/java/com/example/dispute -n`

Expected: every public API used by the formal pages appears in the matrix.

- [ ] **Step 3: Record login scope**

Expected: reports state that login is not tested in this run; role dropdown simulation is used for USER, MERCHANT, CUSTOMER_SERVICE, PLATFORM_REVIEWER, ADMIN where applicable.

### Task 2: Runtime Baseline

**Files:**
- Modify: `docs/acceptance/full-chain-audit/全链路问题清单.md`
- Modify: `docs/acceptance/full-chain-audit/全量回归测试报告.md`

- [ ] **Step 1: Verify Docker infrastructure**

Run: `docker compose ps`

Expected: PostgreSQL, Redis, Temporal, MinIO, Elasticsearch, LiteLLM, and Langfuse are healthy for local development.

- [ ] **Step 2: Verify local app services**

Run:

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8081/actuator/health'
Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health'
curl.exe -I 'http://127.0.0.1:5173/disputes'
```

Expected: Java and Python return UP; frontend returns HTTP 200.

- [ ] **Step 3: Ensure app processes run from the main worktree**

Run: `Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'vite|DisputeApplication|uvicorn' } | Select-Object ProcessId,CommandLine`

Expected: Vite, Java, and Python command lines are rooted in `D:\学习\Project\AfterSaleFlow-Agent`, not `.worktrees`.

### Task 3: Browser First-Round Functional Scan

**Files:**
- Modify: `docs/acceptance/full-chain-audit/全量功能矩阵.md`
- Modify: `docs/acceptance/full-chain-audit/全链路问题清单.md`
- Modify: `docs/acceptance/full-chain-audit/多角色端到端验收报告.md`

- [ ] **Step 1: Open overview as PLATFORM_REVIEWER**

Browser URL: `http://127.0.0.1:5173/disputes`

Expected: overview loads, dispute order list loads from backend, mailbox renders, and no console/runtime error appears.

- [ ] **Step 2: Switch to USER and enter the current room**

Expected: USER can see user-visible dispute orders and navigate to the correct room for the selected case.

- [ ] **Step 3: Switch to MERCHANT and enter the same dispute**

Expected: MERCHANT can see merchant-visible dispute orders, messages, evidence actions, and hearing participation controls where allowed.

- [ ] **Step 4: Switch to PLATFORM_REVIEWER for review and agent console**

Expected: review queue/workbench and agent console load; user/merchant restricted pages remain inaccessible.

### Task 4: Repair Loop

**Files:**
- Modify: affected code files only after root cause is reproduced
- Modify: `docs/acceptance/full-chain-audit/全链路问题清单.md`
- Modify: `docs/acceptance/full-chain-audit/修复变更记录.md`

- [ ] **Step 1: Reproduce each Blocker/Critical issue**

Expected: issue row contains exact route, role, request, response, and log evidence.

- [ ] **Step 2: Add or update failing tests before production fixes**

Expected: each behavior-changing fix has a frontend, backend, or Agent regression test that fails before the fix and passes after the fix.

- [ ] **Step 3: Implement root-cause fix**

Expected: no UI-only masking, no mock success response, no disabled business requirement.

- [ ] **Step 4: Re-run targeted regression**

Expected: the original browser/API path no longer reproduces the issue and related tests pass.

### Task 5: Full Regression and Acceptance Update

**Files:**
- Modify: `docs/acceptance/full-chain-audit/全量回归测试报告.md`
- Modify: `docs/acceptance/full-chain-audit/最终验收清单.md`

- [ ] **Step 1: Run automated suites**

Run:

```powershell
cd frontend; pnpm test; pnpm build
cd ../python-agent-service; pytest tests
cd ../java-api-service; .\mvnw.cmd test
```

Expected: all commands exit 0.

- [ ] **Step 2: Run second browser scan**

Expected: first-round repaired issues remain closed and no new Blocker/Critical issues appear.

- [ ] **Step 3: Update final checklist**

Expected: every formal completion criterion is marked with evidence, limitation, or open issue reference.
