# Evidence Room Fixed Layout and Agent Opening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or focused local execution with TDD. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the evidence room into an intake-room-aligned two-panel experience where the evidence clerk opens the first turn, evidence confidence is persisted/displayed, and users can inspect submitted evidence without blocking hearing entry.

**Architecture:** Keep Java as the business/API boundary, Python agents as LLM workflows, and Vue as the room UI. Reuse existing session-scoped room messages and evidence tables instead of creating duplicate storage. The evidence room remains bilateral at case level but private per actor/session for clerk chat; reviewer sessions can pass permission checks.

**Tech Stack:** Vue 3 + Vitest, Spring Boot + JPA + Flyway, Python FastAPI/LangGraph/LangChain, existing local dev services.

---

### Task 1: Frontend evidence room layout and evidence detail UX

**Files:**
- Modify: `frontend/src/views/disputes/EvidenceRoomView.vue`
- Modify: `frontend/src/views/disputes/EvidenceRoomView.test.js`

- [ ] Write failing Vitest coverage for a fixed two-panel evidence room (`data-evidence-room-layout`, `data-evidence-chat-panel`, `data-evidence-board-panel`).
- [ ] Write failing coverage proving evidence cards open a detail modal.
- [ ] Write failing coverage proving confidence score/level renders on evidence cards.
- [ ] Implement the fixed intake-like layout: left chat panel, right evidence board, outer panel height fixed, inner chat/evidence lists scroll only inside their frames.
- [ ] Move uploader and completion actions into the right board while keeping hearing entry available even when evidence is insufficient or low confidence.
- [ ] Add clickable evidence cards and a modal with parsed text, verification status, confidence, owner, and source metadata.
- [ ] Run the focused Vitest file until green.

### Task 2: Backend evidence catalog confidence and clerk opening endpoint

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceCatalogService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceView.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/EvidenceAgentTurnService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/api/RoomController.java` or the existing room API boundary that owns room messages.
- Modify/Add focused Java tests under `java-api-service/src/test/java/com/example/dispute/room` or `java-api-service/src/test/java/com/example/dispute/evidence`.

- [ ] Write failing Java coverage for catalog responses exposing verification confidence fields when verification data exists.
- [ ] Write failing Java coverage for idempotent evidence clerk opening: first call persists one clerk message for the actor session, repeated calls do not duplicate it.
- [ ] Map existing `EvidenceVerificationEntity` agent/check JSON into `confidenceScore`, `confidenceLevel`, and `verificationFeedback` fields on `EvidenceView`.
- [ ] Add an evidence opening service method that resolves the actor-scoped agent session, reads intake dossier and current evidence, calls the Python evidence clerk with a `ROOM_OPENING` trigger, persists the assistant message, and returns the message view.
- [ ] Expose a frontend-callable endpoint for ensuring the opening message.
- [ ] Run focused Java tests until green.

### Task 3: Python evidence clerk opening prompt contract

**Files:**
- Modify: `python-agent-service/app/agents/evidence_clerk/workflow.py`
- Modify: `python-agent-service/app/agents/evidence_clerk/schemas.py`
- Modify: `python-agent-service/app/agents/prompts/evidence_clerk/evidence_turn.md`
- Modify/Add focused tests under `python-agent-service/tests/agents/test_evidence_clerk_turn.py`

- [ ] Write failing Python coverage for `ROOM_OPENING` producing a clerk message that asks evidence-specific questions based on intake dossier.
- [ ] Add/accept the `ROOM_OPENING` trigger in schemas.
- [ ] Prompt the clerk to ask for source/authenticity/completeness/relevance evidence, not liability or remedy.
- [ ] Ensure opening response can include confidence guidance but does not require uploaded evidence.
- [ ] Run focused Python tests until green.

### Task 4: Frontend API integration and browser E2E

**Files:**
- Modify: `frontend/src/api/rooms.js`
- Modify: `frontend/src/stores/room.js` if the existing store owns room message loading.
- Modify: `frontend/src/views/disputes/EvidenceRoomView.vue`
- Modify focused frontend tests.

- [ ] Add API helper to ensure the evidence clerk opening.
- [ ] On evidence room load, call the helper before/around message refresh so a fresh actor sees the clerk’s first question.
- [ ] Verify actor/session isolation remains intact: current actor sees only their private clerk chat; evidence board can show allowed submitted evidence.
- [ ] Run frontend tests, Java focused tests, Python focused tests.
- [ ] Use the in-app browser to open the evidence room, verify fixed two-panel layout, first clerk message, evidence card modal, upload/list behavior, and hearing entry availability.
- [ ] Commit and push to `main`.
