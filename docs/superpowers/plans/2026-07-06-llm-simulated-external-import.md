# LLM Simulated External Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an overview-page external import button that uses the current demo identity as the initiator and lets the LLM simulate the rest of the external dispute data before importing through the official persistence path.

**Architecture:** Java remains the authoritative writer: it exposes a simulation endpoint, calls the Python Agent for structured simulated external disputes, validates each generated item, and reuses `DisputeImportService.importDispute`. Python Agent only generates DTOs and never writes the database. The frontend calls the Java simulation endpoint and refreshes the overview list.

**Tech Stack:** Spring Boot, Flyway, Vue 3, Vitest, FastAPI/Pydantic, LiteLLM-backed harness.

---

### Task 1: Persist and expose dispute initiator role

**Files:**
- Create: `java-api-service/src/main/resources/db/migration/V018__case_initiator_role.sql`
- Modify: `java-api-service/src/main/java/com/example/dispute/infrastructure/persistence/entity/FulfillmentCaseEntity.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/caseintake/application/CreateCaseCommand.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/caseintake/application/CaseView.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/caseintake/application/CaseApplicationService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/casecore/application/ImportDisputeCommand.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/casecore/application/DisputeImportService.java`

- [ ] Write failing tests asserting created/imported disputes expose `initiatorRole`.
- [ ] Run targeted Java tests and verify failure.
- [ ] Add the Flyway column, entity field, command/view fields, and service mapping.
- [ ] Run targeted Java tests and verify pass.

### Task 2: Enforce single-party intake participation

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomMessageService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomTurnMemoryQueryService.java`
- Modify: `frontend/src/views/disputes/IntakeRoomView.vue`
- Modify: `frontend/src/views/disputes/IntakeRoomView.test.js`

- [ ] Write failing backend tests: non-initiating party cannot post in `INTAKE`, both parties can still post in `EVIDENCE`.
- [ ] Write failing frontend test: non-initiating party sees intake history but no composer.
- [ ] Implement shared initiator checks for intake posting/memory access and frontend composer visibility.
- [ ] Run targeted backend/frontend tests and verify pass.

### Task 3: Add LLM simulated import endpoint

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/application/ExternalDisputeSimulationClient.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/application/SimulateExternalImportCommand.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/application/SimulatedExternalDispute.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/application/SimulatedImportResultView.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/infrastructure/RestClientExternalDisputeSimulationClient.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/casecore/application/DisputeImportService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/casecore/api/InternalDisputeImportController.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/api/SimulateImportRequest.java`

- [ ] Write failing controller/service tests for `POST /internal/disputes/import/simulate`.
- [ ] Implement the Java client and service method that calls Python then reuses `importDispute`.
- [ ] Run targeted controller/service tests and verify pass.

### Task 4: Add Python Agent generator

**Files:**
- Create: `python-agent-service/app/business/simulated_imports.py`
- Modify: `python-agent-service/app/schemas/final_agents.py`
- Modify: `python-agent-service/app/main.py`
- Create or modify: `python-agent-service/tests/test_api.py`

- [ ] Write failing FastAPI test for simulated external import generation.
- [ ] Implement Pydantic request/result schemas and a harness-backed generator with deterministic fallback.
- [ ] Run targeted Python tests and verify pass.

### Task 5: Add overview button and refresh flow

**Files:**
- Modify: `frontend/src/api/disputes.js`
- Modify: `frontend/src/api/disputes.test.js`
- Modify: `frontend/src/views/disputes/DisputeOverviewView.vue`
- Modify: `frontend/src/views/disputes/DisputeOverviewView.test.js`

- [ ] Write failing Vitest tests for the API call and overview button.
- [ ] Add `disputeApi.simulateExternalImport`, import button state, current-role initiator payload, and list refresh.
- [ ] Run targeted frontend tests and verify pass.

### Task 6: Verify and commit

**Files:** all modified files above.

- [ ] Run Java targeted tests for case import, room messages, controller, and application service.
- [ ] Run Python targeted tests.
- [ ] Run frontend targeted tests, then `pnpm test` and `pnpm build`.
- [ ] Use the browser to click the overview import button and confirm the generated external cases appear.
- [ ] Commit and push on `main`.
