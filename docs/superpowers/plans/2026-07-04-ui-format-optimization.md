# AI Native UI Format Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish the current Docker frontend as the visual source of truth so all exposed dispute-room UI states render clearly and the accepted-intake flow appears in the dispute overview.

**Architecture:** Keep the existing Vue room/page components and Spring Boot case lifecycle model. Add small presentation helpers inside the touched Vue components; fix the backend case lifecycle at the `admitToEvidence` boundary so accepted intake-created disputes become first-class dispute cases.

**Tech Stack:** Vue 3, Vite/Vitest, Spring Boot, JUnit 5, AssertJ.

---

### Task 1: Evidence room source type and sealed handoff

**Files:**
- Modify: `frontend/src/views/disputes/EvidenceRoomView.test.js`
- Modify: `frontend/src/views/disputes/EvidenceRoomView.vue`

- [ ] Add a failing test that uploads as USER and expects `sourceType: "USER_UPLOAD"`.
- [ ] Add a failing test that a sealed completion with `next_room: "HEARING"` shows an explicit hearing-entry action instead of immediately navigating.
- [ ] Implement `actorEvidenceSourceType()` in the component and use it for uploads.
- [ ] Replace automatic sealed navigation with an explicit `data-enter-hearing` button.
- [ ] Run: `pnpm --dir frontend vitest run src/views/disputes/EvidenceRoomView.test.js`.

### Task 2: Hearing room source type and ledger empty state

**Files:**
- Modify: `frontend/src/views/disputes/HearingCourtView.test.js`
- Modify: `frontend/src/views/disputes/HearingCourtView.vue`

- [ ] Add a failing test that supplementary evidence as MERCHANT sends `sourceType: "MERCHANT_UPLOAD"`.
- [ ] Add a failing test that no rounds renders a readable empty ledger.
- [ ] Implement the source-type helper and ledger empty state.
- [ ] Run: `pnpm --dir frontend vitest run src/views/disputes/HearingCourtView.test.js`.

### Task 3: Overview and mailbox formatting

**Files:**
- Modify: `frontend/src/views/disputes/DisputeOverviewView.test.js`
- Modify: `frontend/src/views/disputes/DisputeOverviewView.vue`
- Modify: `frontend/src/components/notification/SummonsMailbox.test.js`
- Modify: `frontend/src/components/notification/SummonsMailbox.vue`

- [ ] Add tests that raw risk/current-room/pending-action enums are translated to Chinese labels.
- [ ] Add tests that long IDs are rendered through short visible labels while preserving full IDs in `title`.
- [ ] Implement display-label helpers and text-clamping CSS.
- [ ] Run: `pnpm --dir frontend vitest run src/views/disputes/DisputeOverviewView.test.js src/components/notification/SummonsMailbox.test.js`.

### Task 4: Review queue and workbench structure

**Files:**
- Modify: `frontend/src/views/reviews/ReviewQueueView.test.js`
- Modify: `frontend/src/views/reviews/ReviewQueueView.vue`
- Modify: `frontend/src/views/reviews/ReviewWorkbenchView.test.js`
- Modify: `frontend/src/views/reviews/ReviewWorkbenchView.vue`

- [ ] Add tests that review queue due time is human-formatted and IDs are shortened.
- [ ] Add tests that ReviewPacket claims/issues/evidence/remedy render as structured cards/lists instead of raw JSON.
- [ ] Implement small normalization helpers scoped to the review components.
- [ ] Run: `pnpm --dir frontend vitest run src/views/reviews/ReviewQueueView.test.js src/views/reviews/ReviewWorkbenchView.test.js`.

### Task 5: Accepted intake-created disputes appear in overview

**Files:**
- Modify: `java-api-service/src/test/java/com/example/dispute/caseintake/CaseApplicationServiceTest.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/infrastructure/persistence/entity/FulfillmentCaseEntity.java`

- [ ] Add a failing test that a `TRANSFERRED` intake-created case becomes `DISPUTE` after `admitToEvidence`.
- [ ] Set `caseType = "DISPUTE"` in `admitToEvidence`.
- [ ] Run: `./mvnw -pl java-api-service -Dtest=CaseApplicationServiceTest test`.

### Task 6: Final verification

**Files:**
- No new files expected.

- [ ] Run focused frontend tests for all touched components.
- [ ] Run frontend build: `pnpm --dir frontend build`.
- [ ] Run focused backend test for the case lifecycle fix.
- [ ] Browser-check `/disputes`, the demo hearing room, `/reviews`, and the review workbench.
