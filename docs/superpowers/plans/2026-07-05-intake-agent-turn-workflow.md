# Intake Agent Turn Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the intake-room Agent Turn skeleton where lobby form seed and each user message trigger a Python LangGraph turn, persist room memory, and refresh the right-side dossier scroll.

**Architecture:** Frontend calls Java only. Java remains the source of truth for case state, room messages, memory persistence, and SSE; Python runs one LangGraph turn and returns structured `room_utterance`, `dossier_patch`, `scroll_snapshot`, and `canvas_operations`. Knowledge QA is a stub node for future RAG/MCP integration.

**Tech Stack:** Vue 3/Vite, Spring Boot/JPA/Flyway/PostgreSQL/SSE, Python FastAPI/Pydantic/LangGraph, Vitest, JUnit.

---

### Task 1: Python IntakeTurnGraph API

**Files:**
- Modify: `python-agent-service/app/schemas/final_agents.py`
- Create: `python-agent-service/app/intake_turn.py`
- Modify: `python-agent-service/app/main.py`
- Test: `python-agent-service/tests/agents/test_intake_turn.py`

- [ ] **Step 1: Add failing Python tests**

Create tests that assert `/internal/agents/intake/turn` accepts a lobby seed, returns a first question, produces a scroll snapshot, and marks knowledge-query intent when the user asks a process/rule question.

- [ ] **Step 2: Run the focused Python tests and observe failure**

Run: `pytest tests/agents/test_intake_turn.py -q`

Expected: fails because schemas/endpoint do not exist.

- [ ] **Step 3: Implement Pydantic schemas**

Add `IntakeTurnRequest`, `IntakeTurnMessage`, `IntakeTurnResult`, and small JSON fields with strict typing where possible.

- [ ] **Step 4: Implement `IntakeTurnWorkflow`**

Use `StateGraph` with nodes:

```text
load_context -> classify_intent -> intake_reasoning -> knowledge_qa_stub -> dossier_canvas -> validate_output
```

First version uses deterministic heuristics and existing text, not final prompts.

- [ ] **Step 5: Expose `/internal/agents/intake/turn`**

Use the same `X-Service-Secret`, trace headers, and threadpool pattern as existing internal agent routes.

- [ ] **Step 6: Re-run Python tests**

Run: `pytest tests/agents/test_intake_turn.py -q`

Expected: all new tests pass.

### Task 2: Java room turn memory persistence

**Files:**
- Create: `java-api-service/src/main/resources/db/migration/V016__room_turn_memory.sql`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/RoomTurnMemoryEntity.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/repository/RoomTurnMemoryRepository.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/RoomTurnMemoryView.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/RoomTurnMemoryPersistenceTest.java`

- [ ] **Step 1: Add failing JPA/Flyway persistence test**

Verify an intake agent row can persist `dossier_patch_json`, `scroll_snapshot_json`, `canvas_operations_json`, and can query latest by `case_id + room_type`.

- [ ] **Step 2: Run the focused Java test and observe failure**

Run: `.\mvnw.cmd "-Dtest=RoomTurnMemoryPersistenceTest" test`

Expected: fails because migration/entity/repository do not exist.

- [ ] **Step 3: Add migration, entity, and repository**

Table stores both participant turns and agent turns; latest agent snapshot query orders by `turn_no desc`.

- [ ] **Step 4: Re-run focused Java persistence test**

Run: `.\mvnw.cmd "-Dtest=RoomTurnMemoryPersistenceTest" test`

Expected: pass.

### Task 3: Java AgentTurn orchestration

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeAgentTurnClient.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/RestClientIntakeAgentTurnClient.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeAgentTurnService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomMessageService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/caseintake/application/CaseApplicationService.java`
- Test: relevant room/case intake integration tests.

- [ ] **Step 1: Add failing tests for initial lobby seed**

Creating a dispute should open INTAKE and create an initial agent memory/message from lobby seed.

- [ ] **Step 2: Add failing tests for user message turn**

Posting a `PARTY_TEXT` in INTAKE as user/merchant should save user message and then append an `AGENT_MESSAGE` plus memory snapshot.

- [ ] **Step 3: Implement client and service**

The service builds Python request from lobby seed, latest snapshot, recent turns, and current message. On failure it saves a degraded agent reply instead of throwing to the UI.

- [ ] **Step 4: Wire creation and message posting**

After case creation and after INTAKE participant messages, trigger the service. Keep Java as state authority.

- [ ] **Step 5: Re-run focused Java tests**

Run the new tests and existing room tests.

### Task 4: Frontend intake dossier scroll

**Files:**
- Modify: `frontend/src/views/disputes/IntakeRoomView.vue`
- Create: `frontend/src/components/intake/IntakeDossierScroll.vue`
- Modify: `frontend/src/api/disputes.js` or add `frontend/src/api/intake.js`
- Test: `frontend/src/views/disputes/IntakeRoomView.test.js`

- [ ] **Step 1: Add failing Vitest tests**

Verify the page renders latest scroll snapshot, shows thinking state while a message is submitted, has no “继续补充” button, and exposes only cancel/submit actions.

- [ ] **Step 2: Implement dossier scroll component**

Render snapshot cards, risk stamps, missing fields, and last agent suggestion.

- [ ] **Step 3: Wire SSE and refresh**

On `ROOM_MESSAGE_CREATED` or `INTAKE_DOSSIER_UPDATED`, refresh messages and intake memory.

- [ ] **Step 4: Re-run focused frontend tests**

Run: `pnpm vitest run src/views/disputes/IntakeRoomView.test.js`

Expected: pass.

### Task 5: Acceptance and regression

**Files:**
- Modify: `docs/acceptance/full-chain-audit/全量功能矩阵.md`
- Modify: `docs/acceptance/full-chain-audit/全链路问题清单.md`
- Modify: `docs/acceptance/full-chain-audit/全量回归测试报告.md`

- [ ] **Step 1: Run focused automated tests**

Run Python, Java, and frontend focused suites.

- [ ] **Step 2: Run browser smoke test**

Create a new dispute from `/disputes`, confirm the first agent question appears, send a user supplement, verify the agent reply and right scroll update.

- [ ] **Step 3: Update acceptance docs**

Record the case ID, tests, and remaining non-blocking limitations: real RAG and prompt refinement are deferred.

---

## Self-review

- Spec coverage: lobby seed, message turn, LangGraph, knowledge stub, canvas skill, Java facts, DB memory, frontend buttons, and no “继续补充” button are covered.
- Placeholder scan: no task uses undefined “TBD”; prompt details are explicitly out of scope.
- Scope check: this is a single vertical skeleton; final prompt/RAG/MCP implementation remains a later project.
