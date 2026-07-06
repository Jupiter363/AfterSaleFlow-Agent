# Evidence Room Agent Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make intake confirmation persist the accepted dossier, then make the evidence room usable end-to-end with real uploads, OCR/Markdown parsing, isolated party conversations, and an LLM-backed evidence clerk.

**Architecture:** Reuse the existing room model and `case_intake_dossier` as the intake dossier table. Extend the current Java room-message pipeline so `EVIDENCE` party messages trigger a Python LangGraph evidence clerk turn, with evidence authenticity assessments persisted through existing evidence verification tables. Keep storage in MinIO and parsing in the OCR parser service; do not create a parallel storage path.

**Tech Stack:** Spring Boot, JPA, Flyway/PostgreSQL JSONB, MinIO, FastAPI, LangGraph/LangChain, shared Python harness, Vue 3, Vitest, JUnit.

---

### Task 1: Persist accepted intake dossier on confirmation

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeRoomService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/IntakeRoomServiceTest.java`

- [ ] Write a failing test proving `/intake/confirm` copies the latest `case_intake_dossier.dossier_json` into `FulfillmentCaseEntity.intakeResultJson` before moving to `EVIDENCE_OPEN`.
- [ ] Run `./gradlew test --tests com.example.dispute.room.IntakeRoomServiceTest` and verify the new assertion fails.
- [ ] Inject `CaseIntakeDossierRepository` into `IntakeRoomService` and use the latest `RoomType.INTAKE` dossier JSON as the authoritative accepted intake result, falling back to the existing case field only when no dossier exists.
- [ ] Re-run the same test and verify it passes.

### Task 2: Support Markdown uploads while preserving OCR for images

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceApplicationService.java`
- Modify: `ocr-parser-service/app/parsers.py`
- Test: `java-api-service/src/test/java/com/example/dispute/evidence/EvidenceApplicationServiceTest.java`
- Test: `ocr-parser-service/tests/test_parsers.py`

- [ ] Write failing tests for a `.md` upload with `text/markdown` and parser extraction of Markdown bytes as text.
- [ ] Run the focused Java and OCR parser tests and verify the new tests fail.
- [ ] Add `text/markdown` to upload validation and parse it using the existing text path with metadata `engine=markdown-text`.
- [ ] Re-run the focused tests and verify they pass.

### Task 3: Add Python evidence clerk turn workflow

**Files:**
- Create: `python-agent-service/app/agents/evidence_clerk/schemas.py`
- Create: `python-agent-service/app/agents/evidence_clerk/workflow.py`
- Create: `python-agent-service/app/agents/evidence_clerk/skills/authenticity/authenticity_skill.py`
- Create: `python-agent-service/app/agents/prompts/evidence_clerk/evidence_turn.md`
- Modify: `python-agent-service/app/harness/prompt_composer.py`
- Modify: `python-agent-service/app/main.py`
- Modify: `python-agent-service/app/schemas/final_agents.py`
- Test: `python-agent-service/tests/agents/test_evidence_clerk_turn.py`

- [ ] Write failing tests proving the workflow lives under `app/agents/evidence_clerk`, accepts case dossier + party-scoped evidence + recent turns, calls the shared harness when a runner exists, and outputs only authenticity/relevance questions.
- [ ] Run `pytest tests/agents/test_evidence_clerk_turn.py -q` and verify failure.
- [ ] Implement the schemas, LangGraph workflow, prompt registration, authenticity skill, and `/internal/agents/evidence/turn` endpoint.
- [ ] Re-run the focused Python test and verify it passes.

### Task 4: Trigger evidence clerk from Java evidence-room messages

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/EvidenceAgentTurnCommand.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/EvidenceAgentTurnResult.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/EvidenceAgentTurnClient.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/EvidenceAgentTurnService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/RestClientEvidenceAgentTurnClient.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomMessageService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/EvidenceAgentTurnServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/RoomMessageAndEventServiceTest.java`

- [ ] Write failing tests proving `EVIDENCE` party text is visible only to the speaking party plus trusted platform roles, while catalog evidence remains shared through the evidence APIs.
- [ ] Write failing tests proving an `EVIDENCE` party turn calls the Python evidence clerk and appends an agent message scoped to the same party.
- [ ] Run focused room tests and verify failure.
- [ ] Implement the evidence turn client/service and route `RoomMessageService` to call it only for `RoomType.EVIDENCE` party text.
- [ ] Persist evidence clerk memory in `room_turn_memory` with `room_type=EVIDENCE`.
- [ ] Re-run focused room tests and verify they pass.

### Task 5: Wire the Vue evidence room to the improved flow

**Files:**
- Modify: `frontend/src/views/disputes/EvidenceRoomView.vue`
- Modify: `frontend/src/views/disputes/EvidenceRoomView.test.js`
- Modify: `frontend/src/api/evidence.js`

- [ ] Write failing tests proving the uploader accepts Markdown/images, refreshes messages after a party evidence explanation, and keeps party conversations isolated by relying on server-filtered messages.
- [ ] Run `pnpm vitest run src/views/disputes/EvidenceRoomView.test.js` and verify failure.
- [ ] Implement the front-end changes without changing the current design language.
- [ ] Re-run the focused frontend test and verify it passes.

### Task 6: End-to-end verification

**Files:**
- No new source files unless a failing integration test identifies a gap.

- [ ] Run focused Java, Python, OCR, and frontend tests.
- [ ] Start local services in the current dev mode.
- [ ] In the browser: confirm intake, enter evidence room as initiator, upload Markdown and image evidence, send an evidence authenticity explanation, switch to the counterparty, verify they can enter the evidence room but do not see the other party's private clerk conversation, upload their own evidence, and verify clerk replies.
- [ ] Commit only files touched by this feature and push `main`.
