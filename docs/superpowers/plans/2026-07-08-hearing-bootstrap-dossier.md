# Hearing Bootstrap Dossier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a hearing bootstrap layer that freezes prior intake/evidence room outputs into the hearing audit trail and renders them as visible courtroom opening messages before the judge starts round one.

**Architecture:** Java owns deterministic bootstrap, persistence, idempotency, room-message audit records, and round opening. Harness/Python remains responsible for LLM judge context composition and later reads the frozen hearing snapshot rather than live intake/evidence data. Frontend renders the new courtroom agent roles without exposing backend fields.

**Tech Stack:** Spring Boot, Spring Data JPA, PostgreSQL JSONB/Flyway, Vitest/Vue.

---

### Task 1: Backend bootstrap service

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtBootstrapService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCourtBootstrapServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create tests proving `bootstrap(caseId, actor, traceId)`:

1. reads the latest `case_intake_dossier` and latest `evidence_dossier`;
2. writes one `hearing_stage_record` with `record_type=BOOTSTRAP_DOSSIER_SNAPSHOT`;
3. appends three shared `room_message` entries with sender roles `INTAKE_OFFICER`, `EVIDENCE_CLERK`, `JUDGE`;
4. calls `HearingRoundService.ensureInitialRoundOpen(caseId, evidenceDossierVersion, "hearing-bootstrap")`;
5. is idempotent on replay and does not duplicate messages or snapshot records.

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
cd java-api-service
./mvnw -Dtest=HearingCourtBootstrapServiceTest test
```

Expected: compile failure because `HearingCourtBootstrapService` is not implemented yet.

- [ ] **Step 3: Implement minimal service**

Implement `HearingCourtBootstrapService` with small helpers:

- `bootstrap(...)` public entrypoint;
- `appendAgentMessageIfAbsent(...)` for room messages;
- `recordSnapshotIfAbsent(...)` for `hearing_stage_record`;
- `intakeAnnouncement(...)`, `evidenceAnnouncement(...)`, `judgeOpening(...)` deterministic Chinese display text.

- [ ] **Step 4: Run test to verify it passes**

Run the same Maven command. Expected: PASS.

### Task 2: Wire hearing GET bootstrap

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/api/HearingCollaborationController.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationControllerTest.java` or extend service-level coverage if no focused controller test exists.

- [ ] **Step 1: Write/extend test**

Verify `GET /api/disputes/{caseId}/hearing` calls bootstrap before returning rounds/settlements.

- [ ] **Step 2: Implement controller injection**

Inject `HearingCourtBootstrapService` and call `bootstrap(caseId, actor, traceId)` at the start of `hearing(...)`.

- [ ] **Step 3: Run focused tests**

Run hearing tests and fix only regressions in this scope.

### Task 3: Frontend role rendering

**Files:**
- Modify: `frontend/src/views/disputes/HearingCourtView.vue`
- Modify: `frontend/src/views/disputes/HearingCourtView.test.js`

- [ ] **Step 1: Write failing frontend test**

Pass `initialMessages` containing `INTAKE_OFFICER`, `EVIDENCE_CLERK`, and `JUDGE`, then assert the central transcript shows:

- `案情接待官`;
- `证据书记官`;
- `主审法官`;
- no backend role names.

- [ ] **Step 2: Implement role mapping**

Add explicit transcript role metadata for:

- `INTAKE_OFFICER`;
- `EVIDENCE_CLERK`;
- `JUDGE`;
- `JURY` / `AI_JURY`;
- party roles.

- [ ] **Step 3: Run Vitest**

Run:

```powershell
cd frontend
pnpm vitest run src/views/disputes/HearingCourtView.test.js
```

Expected: PASS.

### Task 4: Verification

**Files:** no new files unless tests expose defects.

- [ ] **Step 1: Backend focused tests**

Run:

```powershell
cd java-api-service
./mvnw -Dtest=HearingCourtBootstrapServiceTest,HearingCollaborationIntegrationTest,HearingCourtOrchestratorTest test
```

- [ ] **Step 2: Frontend build/tests**

Run:

```powershell
cd frontend
pnpm vitest run src/views/disputes/HearingCourtView.test.js
pnpm build
```

- [ ] **Step 3: Browser smoke test**

Open `/disputes/{caseId}/hearing`, confirm the transcript contains the intake/evidence/judge bootstrap messages, refresh once, and confirm they are not duplicated.
