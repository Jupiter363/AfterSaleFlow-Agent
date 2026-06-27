# Formal Order Fulfillment Dispute System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify the complete production-scope order-fulfillment dispute adjudication system described by the controlling development document, with the one-time acceptance checklist as the only completion gate.

**Architecture:** A Docker Compose monorepo contains a Vue 3 frontend, a Java 21/Spring Boot system-of-record and Temporal worker, a FastAPI/LangGraph agent service, and a FastAPI OCR service. PostgreSQL owns business and audit state; Redis is limited to ephemeral state and locks; MinIO owns evidence objects; Elasticsearch owns search projections; LiteLLM is the only LLM gateway and Langfuse records agent traces. Every business path passes through remedy planning, approval policy, mandatory human review, and the deterministic tool executor.

**Tech Stack:** Java 21, Spring Boot 3, Maven, PostgreSQL, Flyway, Redis, MinIO, Elasticsearch, Temporal, Python 3.12, FastAPI, Pydantic, LangGraph, LiteLLM, Langfuse, PaddleOCR/MarkItDown, Vue 3, TypeScript, Vite, Pinia, Vitest, Docker Compose, Nginx.

**Authoritative requirements:**

- `Project Plan/订单履约争议裁决系统_正式版开发文档_Codex主控.md`
- `Project Plan/订单履约争议裁决系统_统一配置说明_Codex执行版.md`
- `Project Plan/订单履约争议裁决系统_正式版一次性验收清单_Codex.md`
- `Project Plan/order_fulfillment_dispute_agentic_architecture.md`

---

### Task 1: Repository, configuration, and deployment skeleton

**Files:**
- Create: `.gitignore`, `.editorconfig`, `.env.example`, `README.md`
- Create: `docker-compose.yml`
- Create: `deploy/{nginx,postgresql,elasticsearch,minio,litellm,langfuse,temporal}/`
- Create: `scripts/{generate-secrets,dev-up,dev-down,dev-reset,init-db,init-minio,init-es,smoke-test}.sh`
- Test: `tests/static/test_repository_contract.py`

- [ ] Write static contract tests for required directories, Compose services, environment variables, secret placeholders, ports, volumes, health checks, and initialization scripts.
- [ ] Run `pytest tests/static/test_repository_contract.py -q` and confirm it fails because the skeleton is absent.
- [ ] Add the monorepo skeleton and deployment/configuration files with no real secret committed.
- [ ] Run `docker compose config` and the static contract tests.
- [ ] Document service boundaries, startup, reset, and secret generation.

### Task 2: Java foundation and database model

**Files:**
- Create: `java-api-service/pom.xml`, `java-api-service/Dockerfile`
- Create: `java-api-service/src/main/java/com/example/dispute/{common,config,modules,integration}/`
- Create: `java-api-service/src/main/resources/application*.yml`
- Create: `java-api-service/src/main/resources/db/migration/V001__init_case_tables.sql` through `V005__init_policy_audit_tables.sql`
- Test: `java-api-service/src/test/java/com/example/dispute/`

- [ ] Write failing tests for `ApiResponse`, error mapping, trace propagation, role/resource authorization, converters, and state transitions.
- [ ] Implement the Spring Boot foundation, centralized exception handling, trace filter, security header authentication, OpenAPI, and actuator health.
- [ ] Write migration contract tests covering all 19 required tables, indexes, unique constraints, timestamps, audit fields, and monetary/time types.
- [ ] Implement entities, domain models, DTOs, converters, repositories, and Flyway migrations without returning entities from controllers.
- [ ] Run `mvn test`.

### Task 3: Case intake, evidence, routing, and non-hearing flows

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/modules/{casecore,intake,evidence,router,regularflow,ruleflow,policy}/`
- Create: `java-api-service/src/main/java/com/example/dispute/integration/{agent,ocr,minio,search}/`
- Test: matching module tests and API tests

- [ ] Write failing API/application tests for case creation/query, degraded intake, missing slots, evidence validation/upload, dossier building, OCR failure tolerance, and all three route decisions.
- [ ] Implement Case Intake without adjudication or execution authority.
- [ ] Implement Evidence Dossier Builder, timeline, claim-issue-evidence matrix, original/desensitized object separation, and search projections.
- [ ] Implement deterministic routing plus regular and rule-based findings; neither path may close a case or bypass downstream review.
- [ ] Run focused tests and then `mvn test`.

### Task 4: Python Agent Service and C1-C6 graph

**Files:**
- Create: `python-agent-service/pyproject.toml`, `python-agent-service/Dockerfile`
- Create: `python-agent-service/app/{api,core,clients,prompts,graphs,agents,services,models}/`
- Test: `python-agent-service/app/tests/`

- [ ] Write failing schema, prompt-injection, node, graph-route, malformed-output, timeout/degradation, and API tests.
- [ ] Implement centralized settings and an OpenAI-compatible client that only targets LiteLLM.
- [ ] Implement versioned prompts and Pydantic outputs for Intake, C1-C6, and Evaluation, explicitly prohibiting final adjudication and tool execution.
- [ ] Implement the LangGraph hearing graph and Langfuse tracing with safe local degradation.
- [ ] Run `pytest`.

### Task 5: OCR Parser Service

**Files:**
- Create: `ocr-parser-service/pyproject.toml`, `ocr-parser-service/Dockerfile`
- Create: `ocr-parser-service/app/{api,core,clients,parsers,services}/`
- Test: `ocr-parser-service/app/tests/`

- [ ] Write failing tests for image, PDF, Word, and Excel parsing, MIME/size/extension rejection, task status, MinIO reads, Java callbacks, and Elasticsearch indexing.
- [ ] Implement asynchronous parse-task APIs and focused parser adapters.
- [ ] Make parsing/indexing failures observable without blocking the core case flow.
- [ ] Run `pytest`.

### Task 6: Temporal workflow and hearing controller

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/modules/workflow/`
- Create: `java-api-service/src/main/java/com/example/dispute/modules/hearing/`
- Test: Temporal test-server workflow tests

- [ ] Write failing tests for start, pause, user/merchant evidence signals, reviewer evidence request, timeouts, activity retries, recovery, and transition to remedy planning.
- [ ] Implement `CaseFulfillmentDisputeWorkflow`, activities, signals, and C0 as deterministic Java workflow control.
- [ ] Persist every hearing node to `hearing_state` and `hearing_record`.
- [ ] Run workflow tests and `mvn test`.

### Task 7: Remedy, approval, mandatory review, and tool execution

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/modules/{remedy,approval,review,executor,tool}/`
- Test: module, authorization, API, and idempotency tests

- [ ] Write failing tests proving every route reaches D, approval policy, human review, and only then execution.
- [ ] Implement Remedy Planner as a mapping/planning layer that cannot re-adjudicate or execute.
- [ ] Implement non-bypassable review tasks and packets with confirm, modify, reject, request-evidence, and escalate decisions; customer service cannot approve.
- [ ] Implement deterministic simulated refund, reship, after-sale close/reject, and notify adapters with risk, approval, idempotency, and failure recording.
- [ ] Prove unapproved actions are denied and duplicate actions are idempotently blocked.

### Task 8: Closure, evaluation, audit, metrics, and security

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/modules/{evaluation,audit}/`
- Modify: Python evaluation agent and shared observability/security components
- Test: closure, evaluation, audit immutability, masking, metrics, and permission tests

- [ ] Write failing tests for closure preconditions, offline-only evaluation, audit coverage, sensitive-data masking, ownership boundaries, internal service authentication, and prompt/tool permission matrices.
- [ ] Implement closure records and post-close evaluation traces/reports.
- [ ] Implement required structured logs, audit events, action records, metrics, slow-request records, and documented alert thresholds.
- [ ] Run all Java and Python tests.

### Task 9: Frontend full workflow

**Files:**
- Create: `frontend/package.json`, `frontend/Dockerfile`, Vite/TypeScript/Vitest configuration
- Create: `frontend/src/{api,stores,router,views,components,utils,tests}/`
- Test: frontend component, store, API, permissions, and validation tests

- [ ] Write failing tests for case state rendering, evidence validation/upload, user and merchant submissions, review packet display, reviewer decisions, button authorization, remedy display, action records, and audit timeline.
- [ ] Implement the Vue 3 pages and typed API client without embedding adjudication or approval rules.
- [ ] Run `npm run lint`, `npm run typecheck`, and `npm run test`.

### Task 10: E2E, performance, CI, and operational documentation

**Files:**
- Create: `tests/{api,e2e,load,fixtures}/`
- Create: `.github/workflows/ci.yml`, `.github/pull_request_template.md`
- Create: `CONTRIBUTING.md`, `CODE_STYLE.md`, `SECURITY.md`
- Create: `docs/{architecture,api,database,deployment,testing,operations}/`

- [ ] Add API tests for every required endpoint and normal/error/idempotent/authorization path.
- [ ] Add regression E2E tests for regular fulfillment, explicit rule, swap fraud, delivered-not-received, reviewer evidence round-trip, tool idempotency, closure, and evaluation.
- [ ] Add reproducible P95 load checks for the five required performance targets.
- [ ] Add CI quality gates for Java, Python ruff/mypy/pytest, frontend ESLint/TypeScript/Vitest, Compose validation, secret scanning, image builds, and smoke tests.
- [ ] Document branching, commits, reviews, release, rollback, adapters, database/storage contracts, security, alerts, and operations.

### Task 11: Compose runtime integration

**Files:**
- Modify: `docker-compose.yml`, all Dockerfiles and deploy configurations
- Test: `scripts/smoke-test.sh`, Compose health checks, E2E tests

- [ ] Generate a local `.env` without exposing secrets and validate `docker compose config`.
- [ ] Build and start PostgreSQL, Redis, MinIO, Elasticsearch, Temporal, Langfuse, LiteLLM, three application services, frontend, and Nginx in dependency order.
- [ ] Verify migrations, buckets, indexes, service health, Nginx routes, persistence, logs, and resets.
- [ ] Run smoke, API, E2E, and performance tests against the containerized system.

### Task 12: Requirement-by-requirement acceptance

**Files:**
- Create: `docs/codex/acceptance-evidence.md`
- Create: `docs/codex/final-acceptance-report.md`

- [ ] Execute every mandatory command from acceptance section 17.2 and retain command summaries.
- [ ] Fill every row in sections 1-20 with PASS/FAIL/PARTIAL/BLOCKED/N/A plus direct evidence.
- [ ] Prove all 14 veto items pass, all three main E2E paths pass, no prohibited technology exists, and no human-review/tool-approval bypass exists.
- [ ] Fix every FAIL/PARTIAL/BLOCKED item and repeat the complete audit.
- [ ] Mark the project complete only when every PASS condition in section 23.1 has current authoritative evidence.
