# AI Native Fulfillment Dispute Hearing System Final Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the existing validated MVP into the final AI Native fulfillment-dispute hearing system, update the repository structure, preserve reusable infrastructure and safety controls, and pass every applicable item in the final acceptance checklist.

**Architecture:** Keep the Docker Compose platform, evidence infrastructure, deterministic Java business boundary, mandatory human review, and idempotent Tool Executor. Replace the old case/router vocabulary with the final dispute model, introduce a governed Python Agent Runtime Harness and six agent roles, split orchestration into five Temporal workflows, and rebuild the frontend as an evidence-linked AI hearing workspace. Use forward-only Flyway migrations and temporary compatibility adapters; remove every old route and API before final acceptance.

**Tech Stack:** Java 21, Spring Boot 3.5, Maven, PostgreSQL, Flyway, Redis, MinIO, Elasticsearch, Temporal 1.35, Python/FastAPI/Pydantic/LangGraph, LiteLLM, Langfuse, Vue 3, Vite, Vitest, Docker Compose, Nginx.

**Authoritative requirements:**

- `Project Plan/AI_Native履约争端审理系统_正式版开发文档_最终版.md`
- `Project Plan/AI_Native履约争端审理系统_架构设计_最终版.md`
- `Project Plan/AI_Native履约争端审理系统_最终技术清单_最终版.md`
- `Project Plan/AI_Native履约争端审理系统_前端交互设计技术方案_正式补充版.md`
- `Project Plan/AI_Native履约争端审理系统_统一配置说明_最终版.md`
- `Project Plan/AI_Native履约争端审理系统_正式版验收清单_最终版.md`

**Completion rule:** No task is complete until its focused tests pass. The project is not complete until all service suites, Compose validation, smoke tests, final E2E scenarios, security scans, and every applicable final acceptance row have fresh evidence.

---

## Target Repository Structure

```text
AfterSaleFlow-Agent/
├── frontend/
│   └── src/
│       ├── api/
│       ├── components/
│       │   ├── agent/
│       │   ├── evidence/
│       │   ├── hearing/
│       │   ├── review/
│       │   └── shared/
│       ├── schemas/
│       ├── stores/
│       ├── views/
│       │   ├── disputes/
│       │   └── reviews/
│       └── router/
├── java-api-service/
│   └── src/main/java/com/example/dispute/
│       ├── casecore/
│       ├── intake/
│       ├── evidence/
│       ├── routing/
│       ├── hearing/
│       ├── deliberation/
│       ├── remedy/
│       ├── review/
│       ├── execution/
│       ├── evaluation/
│       ├── workflow/
│       └── platform/
│           ├── api/
│           ├── audit/
│           ├── config/
│           ├── persistence/
│           └── security/
├── python-agent-service/
│   └── app/
│       ├── api/
│       ├── agents/
│       ├── harness/
│       ├── prompts/
│       ├── schemas/
│       └── skills/
├── ocr-parser-service/
├── deploy/
├── scripts/
├── tests/
│   ├── acceptance/
│   ├── api/
│   ├── e2e/
│   ├── load/
│   ├── security/
│   └── static/
└── docs/
    ├── architecture/
    ├── api/
    ├── database/
    ├── operations/
    └── codex/
```

## Code Comment Standard

- Public Java interfaces, workflow contracts, security policies, Tool Executor invariants, and non-obvious transaction/idempotency rules require concise Javadoc explaining **why the boundary exists**.
- Python Harness protocols, policy decisions, memory scope, loop termination, and guardrail behavior require concise docstrings.
- Vue composables and schema renderers require comments only for security-sensitive or non-obvious state transitions.
- Do not add narration comments that repeat the code. Comments must document authority, invariants, failure behavior, or compatibility constraints.

---

### Task 0: Freeze the validated baseline and create the isolated refactor workspace

**Files:**
- Create: `docs/superpowers/plans/2026-07-02-ai-native-final-refactor.md`
- Preserve: all final documents under `Project Plan/`
- Modify: `.gitignore` only if `.worktrees/` is not ignored

- [ ] **Step 1: Record the pre-refactor state**

Run:

```powershell
git status --short
git log -1 --oneline
```

Expected: only final document replacement files are dirty; production code is still at the validated Phase 16 commit.

- [ ] **Step 2: Commit the final document baseline and this plan**

Stage only `Project Plan/` and this plan, inspect the staged diff, and commit:

```text
docs: establish final AI Native refactor baseline
```

- [ ] **Step 3: Create an isolated worktree**

Create branch `codex/ai-native-final-refactor` in ignored `.worktrees/ai-native-final-refactor`.

- [ ] **Step 4: Verify the baseline**

Run the existing Java, Python Agent, OCR, frontend, static, and Compose configuration tests. If any baseline failure exists, diagnose before feature work.

---

### Task 1: Add final architecture contract tests and repository structure

**Files:**
- Create: `tests/acceptance/test_final_architecture_contract.py`
- Create: `tests/static/test_final_repository_structure.py`
- Create: `docs/architecture/final-module-map.md`
- Modify: `README.md`
- Move/refactor: Java, Python, and frontend packages into the target repository structure

- [ ] **Step 1: Write failing structure tests**

Tests must assert:

```python
assert not (JAVA / "regularflow").exists()
assert not (JAVA / "ruleflow").exists()
assert (PYTHON / "harness" / "profile.py").exists()
assert (PYTHON / "agents" / "deliberation_panel.py").exists()
assert (FRONTEND / "views" / "disputes" / "EvidenceStudioView.vue").exists()
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
python -m pytest tests/static/test_final_repository_structure.py -q
```

Expected: FAIL because the final modules do not yet exist and old flow packages remain.

- [ ] **Step 3: Introduce the target directories without changing behavior**

Move reusable components incrementally, update imports, and add module README/package documentation for ownership boundaries.

- [ ] **Step 4: Verify GREEN**

Run all static tests plus service compilation. The structure test may temporarily allow incomplete final modules but must never allow old flow packages after Task 2.

---

### Task 2: Replace the old domain, routing, API, and persistence vocabulary

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/domain/FulfillmentDisputeCase.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/domain/CaseStatus.java`
- Create: `java-api-service/src/main/java/com/example/dispute/routing/domain/HearingRoute.java`
- Create: `java-api-service/src/main/java/com/example/dispute/routing/domain/AdmissibilityHearingRouter.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/api/DisputeController.java`
- Create: `java-api-service/src/main/resources/db/migration/V007__final_dispute_core.sql`
- Create: `java-api-service/src/main/resources/db/migration/V008__final_versioned_hearing_objects.sql`
- Modify: evidence, remedy, review, execution, audit repositories and entities
- Remove: `regularflow/`, `ruleflow/`, old `RouteType`, and old flow conclusions
- Test: focused domain, migration, API, and router tests

- [ ] **Step 1: Write failing final route tests**

Required contract:

```java
assertThat(router.decide(nonDispute)).isEqualTo(HearingRoute.TRANSFERRED);
assertThat(router.decide(clearLowRiskDispute)).isEqualTo(HearingRoute.SIMPLE_HEARING);
assertThat(router.decide(conflictingHighRiskDispute)).isEqualTo(HearingRoute.FULL_HEARING);
```

- [ ] **Step 2: Verify RED**

Run the focused router test. Expected: compile/test failure because `HearingRoute` and `AdmissibilityHearingRouter` do not exist.

- [ ] **Step 3: Implement the final domain**

Implement legal state transitions and the three final routes. `TRANSFERRED` is terminal inside this system and cannot create a remedy or execution plan.

- [ ] **Step 4: Write failing API contract tests**

Tests require unversioned final endpoints:

```text
POST /api/disputes
GET  /api/disputes
GET  /api/disputes/{caseId}
POST /api/disputes/{caseId}/evidence
GET  /api/disputes/{caseId}/dossiers/{version}
GET  /api/disputes/{caseId}/hearing
POST /api/disputes/{caseId}/cancel
```

- [ ] **Step 5: Implement final API adapters**

Use request/response DTOs, stable cursor pagination, `Idempotency-Key`, `requestId`, and `traceId`. Do not expose `/v2` or `/v3`.

- [ ] **Step 6: Write failing migration tests**

Require the final tables and immutable version references from section 11 of the final development document.

- [ ] **Step 7: Implement forward migrations and data backfill**

Preserve existing local data where deterministic; create final tables, copy reusable case/evidence/review/action data, and mark legacy tables read-only until cutover.

- [ ] **Step 8: Remove old route production code**

Delete old non-dispute flow services and update tests to prove no production path references `REGULAR_FULFILLMENT` or `RULE_BASED_RESOLUTION`.

- [ ] **Step 9: Run focused and full Java tests**

---

### Task 3: Build the Agent Runtime Harness

**Files:**
- Create: `python-agent-service/app/harness/profile.py`
- Create: `python-agent-service/app/harness/policy.py`
- Create: `python-agent-service/app/harness/context.py`
- Create: `python-agent-service/app/harness/memory.py`
- Create: `python-agent-service/app/harness/skills.py`
- Create: `python-agent-service/app/harness/tool_gateway.py`
- Create: `python-agent-service/app/harness/loop.py`
- Create: `python-agent-service/app/harness/validation.py`
- Create: `python-agent-service/app/harness/guardrails.py`
- Create: `python-agent-service/app/harness/interrupts.py`
- Create: `python-agent-service/app/harness/hooks.py`
- Create: `python-agent-service/app/harness/observability.py`
- Create: `python-agent-service/tests/harness/`

- [ ] **Step 1: Write failing Agent Profile tests**

The wished-for API:

```python
profile = AgentProfile.model_validate(payload)
assert profile.authorizes_tool("evidence.read")
assert not profile.authorizes_tool("refund.execute")
```

- [ ] **Step 2: Verify RED, then implement Profile and policy merge**

Default deny any context, skill, or tool not explicitly declared. Record the profile version.

- [ ] **Step 3: Write failing Context Assembly tests**

Prove source/version/access metadata is mandatory, out-of-scope evidence is rejected, and token budgeting is deterministic.

- [ ] **Step 4: Implement Context Builder and scoped memory**

Separate run memory, immutable case memory, and approved experience memory. A case result cannot promote itself to global memory.

- [ ] **Step 5: Write failing Tool Gateway tests**

Prove identity, state, schema, field scope, timeout, redaction, audit, and forbidden write-tool behavior.

- [ ] **Step 6: Implement Tool Gateway and Skill Registry**

Agents never call a database, MinIO, Tool Executor, or arbitrary HTTP endpoint directly.

- [ ] **Step 7: Write failing Loop/Validation/Guardrail tests**

Cover iteration, tool, model, token and deadline budgets; stagnation; structured output repair; prompt injection; invented citation; final-decision language; and human interrupt.

- [ ] **Step 8: Implement the controlled loop**

Use explicit stop reasons and bounded repair. Validation failure after the repair budget returns an interrupt, not free text.

- [ ] **Step 9: Implement hooks and observability**

Record trace identifiers, versions, tools, validation, risks, interrupt reason, token usage, latency, and cost without logging sensitive evidence.

- [ ] **Step 10: Run Harness tests and the existing Python suite**

---

### Task 4: Migrate and add the six final agent roles

**Files:**
- Create: `python-agent-service/app/agents/dispute_intake_officer.py`
- Create: `python-agent-service/app/agents/evidence_clerk.py`
- Create: `python-agent-service/app/agents/presiding_judge.py`
- Create: `python-agent-service/app/agents/deliberation_panel.py`
- Create: `python-agent-service/app/agents/review_copilot.py`
- Create: `python-agent-service/app/agents/evaluation_agent.py`
- Create: `python-agent-service/app/schemas/`
- Create: final role prompts under `python-agent-service/app/prompts/`
- Create: `python-agent-service/app/skills/`
- Modify: existing C1-C6 graph, intake, evaluation, LLM, tracing, and FastAPI routes
- Test: agent role, schema, permission, prompt injection, citation, and API tests

- [x] **Step 1: Migrate Intake to Dispute Intake Officer**

Write failing tests proving it distinguishes dispute vs non-dispute, cites submission text, and has no adjudication/execution authority. Wrap the existing intake prompt and schema in Harness.

- [x] **Step 2: Implement Evidence Clerk**

Write failing tests for catalog, timeline, claim-issue-evidence matrix, conflicts, gaps, duplicate groups, parser warnings, immutable dossier version, and “no liability conclusion”.

- [x] **Step 3: Migrate C1-C6 into one Presiding Judge identity**

Keep stage schemas and prompts, but execute them under one Profile and Harness loop. Stages are not independent agents. Require `non_final=true` for C6.

- [x] **Step 4: Implement the five Critics**

Write separate failing tests for Evidence, Rule, Risk, Remedy, and Fairness outputs. Freeze common inputs and preserve minority major objections.

- [x] **Step 5: Implement Panel Aggregator**

Generate `DeliberationReport` with consensus, disagreements, major risks, and required revisions. It cannot create an approval or execution action.

- [x] **Step 6: Implement Review Copilot**

Answer only within the current ReviewPacket, cite evidence/rules/panel findings, distinguish fact/inference/suggestion, and expose no approve/execute method.

- [x] **Step 7: Migrate Evaluation Agent**

Keep it offline-only and prevent writes to historical case state or automatic publication.

- [ ] **Step 8: Replace internal APIs**

Expose only:

```text
/internal/agents/intake/analyze
/internal/agents/evidence/build
/internal/agents/hearing/run-stage
/internal/agents/deliberation/run
/internal/agents/review-copilot/query
/internal/agents/evaluation/analyze
```

All six final routes are live. Removal of the compatibility
`/agent-api/v1/...` routes is intentionally combined with the global legacy
production-path removal in Task 8 so the Java client is not broken between
commits.

- [x] **Step 9: Run all Python Agent tests**

---

### Task 5: Split orchestration into the five final Temporal workflows

**Files:**
- Create: `workflow/temporal/FulfillmentDisputeWorkflow.java`
- Create: `workflow/temporal/DisputeHearingWorkflow.java`
- Create: `workflow/temporal/DeliberationPanelWorkflow.java`
- Create: `workflow/temporal/HumanReviewWorkflow.java`
- Create: `workflow/temporal/ExecutionWorkflow.java`
- Create: matching implementations, activities, commands, results, snapshots, and tests
- Modify: worker registration, workflow application service, review signals, and execution integration
- Retire: `CaseFulfillmentDisputeWorkflow`

- [x] **Step 1: Write failing main workflow tests**

Prove idempotent start, `TRANSFERRED` terminal behavior, simple/full hearing selection, child-workflow orchestration, and mandatory human review.

- [x] **Step 2: Implement FulfillmentDisputeWorkflow**

Document with Javadoc that Workflow owns deterministic control but cannot perform open-ended cognition.

- [x] **Step 3: Write failing hearing workflow tests**

Cover C1-C6 order, evidence Signal/Timer, bounded rounds, recovery, validation interrupt, and full trace persistence.

- [x] **Step 4: Implement DisputeHearingWorkflow**

All model/network/database access stays in Activities.

- [x] **Step 5: Write and implement Panel workflow tests**

Cover risk-based critic selection, frozen inputs, parallel partial failure, major-objection preservation, timeout, and revision/manual paths.

- [x] **Step 6: Write and implement Human Review workflow tests**

Validate reviewer, role, packet version, action hash, expiry, approve, modify-and-approve, return, reject, and escalate.

- [x] **Step 7: Write and implement Execution workflow tests**

Validate approval snapshot, dependency order, idempotency, unknown external result lookup, bounded retry, and manual handoff.

- [x] **Step 8: Use new workflow types/task queue**

Do not alter running legacy history in place. Route new final disputes to new workflow types, then retire legacy code after no active legacy runs remain.

The Worker registers both histories during migration, while
`WorkflowApplicationService` starts only `FulfillmentDisputeWorkflow` for new
disputes. Legacy workflow code remains replay-compatible until the final
legacy cleanup gate confirms there are no active old histories.

- [x] **Step 9: Run Temporal focused and full Java tests**

---

### Task 6: Upgrade ReviewPacket, policy, audit, and deterministic execution

**Files:**
- Create/modify: final immutable `ReviewPacket`, `HumanReviewRecord`, `ApprovalPolicyDecision`, `ActionRecord`
- Modify: `remedy/`, `review/`, `execution/`, `evaluation/`, and audit modules
- Test: policy, authority, stale packet, action hash, idempotency, audit, and closure tests

- [x] **Step 1: Write failing immutable ReviewPacket tests**

Require frozen case, dossier, issue, draft, deliberation, remedy, ruleset, prompt, skill, and profile versions.

- [x] **Step 2: Implement packet and human record versioning**

Comments must explain why packet mutation is forbidden after review begins.

- [x] **Step 3: Write failing approval tests**

Prove every action has `autoApprove=false`, required reviewer role/count, allowed/forbidden actions, and policy version.

- [x] **Step 4: Upgrade Tool Executor tests**

Reject missing approval, expired approval, unauthorized reviewer, stale packet, action hash mismatch, and duplicate execution.

- [x] **Step 5: Preserve reusable deterministic adapters**

Keep existing refund/reship/close/notify adapter boundaries while replacing old route-source assumptions.

- [x] **Step 6: Complete audit and closure**

Every final decision and action must trace to evidence, rule, Agent Run, ReviewPacket, HumanReviewRecord, and external result.

- [x] **Step 7: Run security, review, execution, closure, and full Java tests**

---

### Task 7: Rebuild the AI Native frontend workspace

**Files:**
- Create: `frontend/src/router/index.js`
- Create: `frontend/src/schemas/generativeUi.js`
- Create: `frontend/src/stores/{dispute,evidence,hearing,review,agentRun}.js`
- Create: `frontend/src/views/disputes/{NewDisputeView,DisputeListView,DisputeWorkspaceView,EvidenceStudioView,HearingCourtView,DeliberationPanelView}.vue`
- Create: `frontend/src/views/reviews/ReviewWorkbenchView.vue`
- Create: component directories from the target structure
- Modify: API client, styles, app shell, tests
- Remove: legacy `/cases` routes and monolithic views after final pages pass

- [ ] **Step 1: Write failing route and navigation tests**

Require:

```text
/disputes/new
/disputes
/disputes/:caseId
/disputes/:caseId/evidence
/disputes/:caseId/hearing
/disputes/:caseId/deliberation
/reviews
/reviews/:reviewId
```

- [ ] **Step 2: Implement the final router and API client**

Use unversioned final API paths. Frontend never calls Python Agent/OCR/Tool Executor directly.

- [ ] **Step 3: Write failing Generative UI schema tests**

Whitelist component and action types; reject arbitrary HTML, scripts, URLs, API paths, and approval/execution actions generated by the model.

- [ ] **Step 4: Implement shared Agent UI**

Build intent-first input, dynamic workspace, progress timeline, trace status, source citations, safe renderer, error/empty/degraded states.

- [ ] **Step 5: Implement Evidence Studio**

Catalog, timeline, matrix, original/parsed/summary distinction, gaps, conflicts, parser warnings, safe preview, and Evidence Clerk query.

- [ ] **Step 6: Implement Hearing Court and Panel**

Display C1-C6, non-final labels, citations, revisions, five Critic tabs, severity, disagreements, and major objections.

- [ ] **Step 7: Implement Review Workbench**

Render frozen ReviewPacket, evidence/rules/draft/panel/remedy side by side, Review Copilot, role-aware actions, reason requirements, and action confirmation.

- [ ] **Step 8: Run Vitest and production build**

---

### Task 8: Final configuration, documentation, and compatibility cleanup

**Files:**
- Modify: `.env.example`, service settings, `docker-compose.yml`, Nginx routes, health checks
- Modify: `README.md`, `docs/architecture/README.md`, `docs/api/README.md`, `docs/database/README.md`, `docs/deployment/README.md`
- Remove: legacy APIs, route enums, workflow, packages, frontend routes, tests, and stale documentation

- [ ] **Step 1: Add failing configuration contract tests**

Require Profile/Prompt/Skill/Ruleset versions, loop budgets, panel/copilot flags, validation/guardrail enabled, and no bypass flags.

- [ ] **Step 2: Implement final configuration**

Keep the same Compose service inventory. New agents remain in `python-agent-service`; do not add Kafka, Kubernetes, service mesh, or vector database.

- [ ] **Step 3: Update Nginx and OpenAPI**

Expose `/api/disputes`, `/api/reviews`, and health endpoints. Do not expose internal Agent, parser, data, or Temporal ports.

- [ ] **Step 4: Remove compatibility code**

Search production source for old product name, `/api/v1`, `REGULAR_FULFILLMENT`, `RULE_BASED_RESOLUTION`, old workflow type, and `/cases` frontend routes. The final acceptance test must require zero production hits.

- [ ] **Step 5: Update repository documentation**

Document the final module map, data ownership, Agent authority, workflow topology, API, local operation, security invariants, and testing commands.

---

### Task 9: Full acceptance and remediation loop

**Files:**
- Create: `tests/acceptance/test_final_acceptance_contract.py`
- Create: `tests/security/test_final_security_contract.py`
- Replace: API/E2E/load fixtures and scenarios with final ones
- Create: `docs/codex/final-ai-native-acceptance-evidence.md`
- Create: `docs/codex/final-ai-native-acceptance-report.md`

- [ ] **Step 1: Convert every checklist row into evidence**

Map sections 1–18 of the final acceptance checklist to an automated test, command output, runtime observation, or reviewed artifact. No row may rely only on a document claim.

- [ ] **Step 2: Run service-local suites**

```powershell
java-api-service\mvnw.cmd test
python -m pytest python-agent-service/tests -q
python -m pytest ocr-parser-service/tests -q
pnpm --dir frontend test
pnpm --dir frontend build
python -m pytest tests/static tests/acceptance tests/security -q
```

- [ ] **Step 3: Run formatting and contract checks**

Run Java formatter/checks, Python Ruff, frontend build, secret scan, `docker compose config --quiet`, and migration validation.

- [ ] **Step 4: Build the isolated runtime**

Start all Compose services, wait for health, initialize databases/buckets/indexes, and verify only allowed ports/routes are exposed.

- [ ] **Step 5: Run final API/E2E/load scenarios**

Required scenarios:

```text
non-dispute transfer and terminal closure
simple hearing with mandatory review
delivered-not-received with evidence round trip
return-swap fraud with panel and elevated review
human return-for-evidence and workflow resume
stale packet/action hash rejection
unknown external result without duplicate action
case closure and offline evaluation
```

- [ ] **Step 6: Fill the final report**

Every row receives PASS/FAIL/PARTIAL/BLOCKED/N/A plus direct evidence. A veto failure makes the result FAIL.

- [ ] **Step 7: Remediation loop**

For every failure, first add or retain the reproducing failing test, diagnose with `systematic-debugging`, implement the minimal fix, rerun focused tests, then rerun the complete acceptance suite.

- [ ] **Step 8: Completion gate**

Stop only when:

```text
all veto items PASS
all applicable mandatory rows PASS
all service tests PASS
all final E2E scenarios PASS
Compose runtime healthy
no prohibited old production path remains
final report contains current evidence
```
