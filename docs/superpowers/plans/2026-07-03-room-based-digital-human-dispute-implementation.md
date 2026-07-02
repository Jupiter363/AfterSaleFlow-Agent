# Room-Based Digital Human Dispute System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the completed AI Native dispute core into a room-based digital-human product with a dispute overview, intake room, evidence room, shared hearing court, in-platform summons inbox, two-hour evidence deadline, three-hour/three-round hearing, versioned settlement, conditional deliberation, Figma designs, Vue implementation, and end-to-end acceptance.

**Architecture:** Preserve the completed Agent Harness, six Agent roles, five Temporal workflows, immutable evidence/review artifacts, mandatory human review, and deterministic execution chain. Add a Java-owned collaboration layer for participants, rooms, immutable messages, server-authoritative clocks, replayable case events, evidence verification, settlement confirmation, notifications, and role-specific projections; Temporal remains the source of truth for deadlines and phase transitions. Design each role-aware room in Figma after the contracts run, then map approved nodes into Vue 3 components.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring MVC/SSE, Spring Security, JPA, PostgreSQL/Flyway, Redis, MinIO, Temporal 1.35, Python/FastAPI/Pydantic, Vue 3, Vue Router, Element Plus, Vite, Vitest, Figma MCP, Docker Compose.

**Authoritative specification:**

- `docs/superpowers/specs/2026-07-03-room-based-digital-human-dispute-design.md`

**Existing completed baseline:** Commits through `04bdf95` implement the final domain, Agent Harness, final Agent roles, five Temporal workflows, immutable ReviewPacket, policy, human review, execution, audit, and evaluation. Commit `660df2e` freezes the approved room-based product design. Do not reimplement those foundations.

**Completion rule:** Documentation is updated before code. Every code task starts with a failing focused test. The project is complete only after service tests, migration tests, frontend tests/build, Docker Compose smoke tests, all new E2E scenarios, visual checks against approved Figma nodes, and every applicable final acceptance row pass with fresh evidence.

---

## Target Module Additions

```text
java-api-service/src/main/java/com/example/dispute/
├── casecore/          # source/import, overview projection, participants
├── room/              # room state, immutable messages, SSE case events
├── notification/      # summons inbox, transactional outbox
├── evidence/          # verification and party-completion semantics
├── hearing/           # rounds, settlement proposals and confirmations
└── workflow/          # Temporal timers, signals and forced convergence

frontend/src/
├── api/               # disputes, rooms, evidence, hearing, notifications, reviews
├── components/
│   ├── avatar/        # 2D digital human and state renderer
│   ├── overview/      # dispute rail and hearing-adventure map
│   ├── room/          # room shell, conversation, countdown
│   ├── evidence/      # upload desks, shared ledger, verification
│   ├── hearing/       # court stage, rounds, settlement, panel
│   ├── notification/  # summons mailbox
│   └── review/        # reviewer-only copilot and actions
├── stores/
└── views/disputes/
```

## Shared Contract Names

Use these values consistently across Java, Python, SQL, frontend schemas, docs, and tests:

```text
CaseSourceType:
  EXTERNAL_IMPORT | INTAKE_CREATED

RoomType:
  INTAKE | EVIDENCE | HEARING | REVIEW

RoomStatus:
  LOCKED | OPEN | WAITING | SEALED | CLOSED

PhaseClockType:
  EVIDENCE_SUBMISSION | HEARING

PhaseClockStatus:
  SCHEDULED | RUNNING | COMPLETED_EARLY | EXPIRED | CANCELLED

EvidenceVerificationStatus:
  VERIFIED | PLAUSIBLE | SUSPICIOUS | REJECTED | NEEDS_HUMAN_REVIEW

DigitalHumanState:
  IDLE | LISTENING | THINKING | SPEAKING | COMPLETED | HANDOFF | ERROR

HearingStopReason:
  FACTS_SUFFICIENT | SETTLEMENT_CONFIRMED | MAX_ROUNDS | DEADLINE_EXPIRED
```

Production defaults:

```text
EVIDENCE_WINDOW=PT2H
HEARING_WINDOW=PT3H
MAX_HEARING_ROUNDS=3
SSE_HEARTBEAT=PT15S
```

---

### Task 1: Make `Project Plan` the room-based final source of truth

**Files:**
- Modify: `Project Plan/AI_Native履约争端审理系统_架构与技术文档改造版.md`
- Modify: `Project Plan/AI_Native履约争端审理系统_架构设计_最终版.md`
- Modify: `Project Plan/AI_Native履约争端审理系统_正式版开发文档_最终版.md`
- Modify: `Project Plan/AI_Native履约争端审理系统_前端交互设计技术方案_正式补充版.md`
- Modify: `Project Plan/AI_Native履约争端审理系统_最终技术清单_最终版.md`
- Modify: `Project Plan/AI_Native履约争端审理系统_统一配置说明_最终版.md`
- Modify: `Project Plan/AI_Native履约争端审理系统_正式版验收清单_最终版.md`
- Modify: `tests/static/test_repository_contract.py`
- Create: `tests/static/test_room_based_document_contract.py`

- [ ] **Step 1: Add the failing document contract**

Create a static test that reads every final document as UTF-8 and asserts the approved vocabulary:

```python
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLAN = ROOT / "Project Plan"
DOCS = list(PLAN.glob("AI_Native履约争端审理系统_*.md"))


def corpus() -> str:
    return "\n".join(path.read_text(encoding="utf-8") for path in DOCS)


def test_room_based_final_contract():
    text = corpus()
    for required in (
        "争议办理总览",
        "争议接待室",
        "证据书记官室",
        "小法庭",
        "传票信箱",
        "PT2H",
        "PT3H",
        "MAX_HEARING_ROUNDS=3",
        "settlement_proposal",
        "Last-Event-ID",
    ):
        assert required in text
    for prohibited in (
        "/api/v2",
        "/api/v3",
        "普通履约流",
        "短信供应商",
    ):
        assert prohibited not in text
```

- [ ] **Step 2: Verify the document contract is red**

Run:

```powershell
D:\miniconda\python.exe -m pytest tests/static/test_room_based_document_contract.py -q
```

Expected: FAIL because the current final documents do not define rooms, the two clocks, settlement, inbox, and SSE replay consistently.

- [ ] **Step 3: Rewrite the product and architecture narrative**

Update the architecture documents so the only mainline is:

```text
争议订单导入或接待官创建
→ 争议接待室
→ 双方加入案件
→ 证据书记官室（PT2H）
→ 小法庭（PT3H，最多三轮）
→ 按需 AI 评审团
→ 平台终审
→ 确定性执行
```

State explicitly that the overview contains only imported or intake-created dispute orders, not ordinary orders.

- [ ] **Step 4: Replace the frontend information architecture**

Document these routes and role variants:

```text
/disputes
/disputes/:caseId/intake
/disputes/:caseId/evidence
/disputes/:caseId/hearing
/disputes/:caseId/outcome
/reviews
/reviews/:reviewId
```

Remove the independent public deliberation page. The panel appears inside the hearing/review experience only when triggered.

- [ ] **Step 5: Add exact API, schema, event, configuration, and error contracts**

Document the endpoints from the approved specification and add:

```text
ROOM_NOT_OPEN
ROOM_ALREADY_SEALED
PHASE_DEADLINE_EXPIRED
HEARING_ROUND_LIMIT_REACHED
SETTLEMENT_VERSION_CONFLICT
SETTLEMENT_CONFIRMATION_CONFLICT
EVIDENCE_QUARANTINED
EVENT_CURSOR_EXPIRED
NOTIFICATION_NOT_VISIBLE
```

- [ ] **Step 6: Add page-specific Figma prompts**

For every page, include the design objective, required content, digital-human role/state, role variants, empty/loading/error/timeout states, mobile behavior, and prohibited patterns. The prompt must say “light, playful, cartoon court; no black-gold solemn style; no traditional ecommerce admin layout.”

- [ ] **Step 7: Expand technical and acceptance checklists**

Add direct rows for import idempotency, participant authorization, evidence completion, both clocks, forced convergence, settlement double confirmation, conditional panel, SSE replay/filtering, inbox outbox/idempotency, avatar reduced motion, Figma parity, and full E2E.

- [ ] **Step 8: Verify and commit documentation**

Run:

```powershell
D:\miniconda\python.exe -m pytest tests/static/test_room_based_document_contract.py tests/static/test_repository_contract.py -q
rg -n "/api/v[23]|普通履约流|短信供应商" "Project Plan"
```

Expected: tests PASS; `rg` returns no conflicting production requirement.

Commit:

```text
docs: define room-based dispute product baseline
```

---

### Task 2: Add collaboration migrations and seeded dispute orders

**Files:**
- Create: `java-api-service/src/main/resources/db/migration/V010__case_rooms_and_participants.sql`
- Create: `java-api-service/src/main/resources/db/migration/V011__evidence_verification_and_hearing_settlement.sql`
- Create: `java-api-service/src/main/resources/db/migration/V012__case_events_and_notification_outbox.sql`
- Modify: `java-api-service/src/test/java/com/example/dispute/database/MigrationIntegrationTest.java`
- Modify: `tests/static/test_final_migration_contract.py`

- [ ] **Step 1: Add failing migration assertions**

Extend the Testcontainers migration test:

```java
assertThat(tableNames).contains(
        "case_participant",
        "case_room",
        "room_message",
        "case_phase_clock",
        "evidence_verification",
        "evidence_party_completion",
        "hearing_round",
        "settlement_proposal",
        "settlement_confirmation",
        "notification",
        "notification_outbox");

assertThat(uniqueConstraints)
        .contains("(source_system, external_case_ref)")
        .contains("(case_id, room_type)")
        .contains("(proposal_id, participant_role)")
        .contains("(business_event_key, recipient_id)");
```

- [ ] **Step 2: Verify migration tests are red**

Run:

```powershell
.\mvnw.cmd -Dtest=MigrationIntegrationTest test
```

from `java-api-service`.

Expected: FAIL because V010–V012 and the new tables do not exist.

- [ ] **Step 3: Implement V010**

Add `source_type`, `source_system`, `external_case_ref`, `current_room`, and `current_deadline_at` to `fulfillment_dispute_case`. Create participants, rooms, immutable messages, and phase clocks with check constraints using the shared enums.

Seed six deterministic dispute rows:

```text
USER: INTAKE_PENDING, EVIDENCE_OPEN, HEARING_OPEN
MERCHANT: EVIDENCE_OPEN, REVIEW_PENDING
USER + MERCHANT: CLOSED
```

Use actor ids already supported by the local actor switcher:

```text
user-local
merchant-local
reviewer-local
```

- [ ] **Step 4: Implement V011**

Create versioned evidence verification, party completion, hearing rounds, settlement proposals, and settlement confirmations. Reject duplicate confirmation of the same proposal version by database constraint.

- [ ] **Step 5: Implement V012**

Add `sequence_no`, `room_id`, `audience_json`, and `event_key` to `case_timeline_event`; create notification and outbox tables. Use a unique event key and recipient key to prevent duplicate summons.

- [ ] **Step 6: Verify migrations and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=MigrationIntegrationTest test
D:\miniconda\python.exe -m pytest ..\tests\static\test_final_migration_contract.py -q
```

Expected: PASS with Flyway applying V001 through V012 from an empty PostgreSQL database.

Commit:

```text
feat: add room collaboration database model
```

---

### Task 3: Implement dispute overview, import, participants, and intake transition

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/domain/CaseSourceType.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/application/DisputeOverviewItemView.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/application/DisputeImportService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/casecore/api/InternalDisputeImportController.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/domain/RoomType.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/domain/RoomStatus.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/ParticipantService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeRoomService.java`
- Create: JPA entities/repositories for `case_participant`, `case_room`, and `case_phase_clock`
- Modify: `java-api-service/src/main/java/com/example/dispute/casecore/api/DisputeController.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/caseintake/application/CaseApplicationService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/casecore/DisputeOverviewApiTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/IntakeRoomServiceIntegrationTest.java`

- [ ] **Step 1: Write the failing overview/import test**

```java
mockMvc.perform(get("/api/disputes")
        .with(user(actor("user-local", "USER"))))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.items[0].sourceType").exists())
    .andExpect(jsonPath("$.data.items[0].currentRoom").exists())
    .andExpect(jsonPath("$.data.items[0].deadlineAt").exists())
    .andExpect(jsonPath("$.data.items[0].pendingAction").exists());

mockMvc.perform(post("/internal/disputes/import")
        .header("X-Service-Identity", "external-dispute-adapter")
        .header("Idempotency-Key", "import-ext-1001")
        .contentType(APPLICATION_JSON)
        .content(importPayload("OMS", "EXT-1001")))
    .andExpect(status().isCreated());
```

Repeat the import and assert the same case id is returned.

- [ ] **Step 2: Verify red**

Run:

```powershell
.\mvnw.cmd -Dtest=DisputeOverviewApiTest test
```

Expected: FAIL because overview fields and internal import do not exist.

- [ ] **Step 3: Implement import and overview projection**

`DisputeImportService.importDispute` must use `(sourceSystem, externalCaseRef)` as its natural idempotency key and create `INTAKE_PENDING` rows only. `GET /api/disputes` must filter by `case_participant` or the existing user/merchant ownership fields and must never return ordinary orders.

- [ ] **Step 4: Write the failing intake transition test**

```java
IntakeConfirmation accepted = service.confirm(
        caseId,
        actor("user-local", USER),
        new IntakeConfirmation(true, "SIGNED_NOT_RECEIVED", "HIGH"));

assertThat(accepted.caseStatus()).isEqualTo(EVIDENCE_OPEN);
assertThat(participants.roles(caseId)).containsExactlyInAnyOrder(USER, MERCHANT);
assertThat(rooms.get(caseId, EVIDENCE).status()).isEqualTo(OPEN);
assertThat(clocks.running(caseId, EVIDENCE_SUBMISSION).deadlineAt())
        .isEqualTo(clock.instant().plus(Duration.ofHours(2)));
```

Add a second test proving `NOT_ADMISSIBLE` creates no merchant participant, clock, or evidence room access.

- [ ] **Step 5: Implement intake confirmation**

Persist the Agent analysis separately from the party confirmation. Accepted cases invite both parties and signal the main Workflow. Rejected cases transition once to `NOT_ADMISSIBLE` and remain queryable only as ended records.

- [ ] **Step 6: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=DisputeOverviewApiTest,IntakeRoomServiceIntegrationTest test
```

Expected: PASS.

Commit:

```text
feat: add dispute overview and intake rooms
```

---

### Task 4: Add immutable room messages, replayable SSE, and summons inbox

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/RoomMessageService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/CaseEventService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/api/RoomController.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/api/CaseEventController.java`
- Create: `java-api-service/src/main/java/com/example/dispute/notification/application/NotificationService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/notification/application/NotificationOutboxPublisher.java`
- Create: `java-api-service/src/main/java/com/example/dispute/notification/api/NotificationController.java`
- Create: entities/repositories for messages, timeline events, notifications, and outbox
- Test: `java-api-service/src/test/java/com/example/dispute/room/RoomAndEventApiIntegrationTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/notification/NotificationApiIntegrationTest.java`

- [ ] **Step 1: Write failing room and SSE tests**

```java
mockMvc.perform(post("/api/disputes/{caseId}/rooms/INTAKE/messages", caseId)
        .with(user(userActor))
        .header("Idempotency-Key", "msg-1")
        .contentType(APPLICATION_JSON)
        .content("""{"messageType":"PARTY_TEXT","text":"物流显示签收，但我没收到"}"""))
    .andExpect(status().isCreated())
    .andExpect(jsonPath("$.data.sequenceNo").value(1));

mockMvc.perform(get("/api/disputes/{caseId}/events", caseId)
        .with(user(userActor))
        .header("Last-Event-ID", "0")
        .accept(TEXT_EVENT_STREAM))
    .andExpect(request().asyncStarted());
```

Add assertions that merchant-private events are absent from the user stream and replay begins after the supplied sequence.

- [ ] **Step 2: Verify red**

Run:

```powershell
.\mvnw.cmd -Dtest=RoomAndEventApiIntegrationTest test
```

- [ ] **Step 3: Implement room messages and event ordering**

Allowed message types:

```text
PARTY_TEXT
PARTY_EVIDENCE_REFERENCE
PARTY_CONFIRMATION
AGENT_MESSAGE
SYSTEM_EVENT
REVIEWER_NOTE
```

Reject messages to locked, sealed, or unauthorized rooms. Write the message and timeline event in one transaction. Allocate monotonic per-case sequence numbers under a row lock.

- [ ] **Step 4: Implement SSE replay**

Use Spring MVC `SseEmitter`. On connect:

1. authorize case membership;
2. replay events with `sequence_no > Last-Event-ID`;
3. filter by `audience_json`;
4. subscribe the emitter to new committed events;
5. send a heartbeat every configured interval;
6. remove completed or timed-out emitters.

- [ ] **Step 5: Write failing inbox tests**

```java
notificationService.on(new EvidenceRoomOpened(caseId, "event:evidence-open"));
notificationService.on(new EvidenceRoomOpened(caseId, "event:evidence-open"));

assertThat(repository.countByBusinessEventKey("event:evidence-open")).isEqualTo(2);
// one recipient row for USER and one for MERCHANT, not duplicates per recipient

mockMvc.perform(post("/api/notifications/{id}/read", notificationId)
        .with(user(userActor)))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.data.readAt").isNotEmpty());
```

- [ ] **Step 6: Implement notification outbox and API**

Support summons, evidence-open, deadline-warning, hearing-open, supplement-request, settlement-confirmation, review-pending, final-decision, execution-complete, and manual-handoff templates. Notification deep links must point only to authorized internal routes.

- [ ] **Step 7: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=RoomAndEventApiIntegrationTest,NotificationApiIntegrationTest test
```

Commit:

```text
feat: add case event stream and summons inbox
```

---

### Task 5: Implement the two-hour evidence room and verification model

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/evidence/domain/EvidenceVerificationStatus.java`
- Create: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceVerificationService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceCompletionService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/evidence/application/RoleScopedEvidenceView.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/api/EvidenceController.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceApplicationService.java`
- Modify: evidence entities/repositories
- Test: `java-api-service/src/test/java/com/example/dispute/evidence/EvidenceRoomIntegrationTest.java`
- Test: `python-agent-service/tests/agents/test_final_agents.py`

- [ ] **Step 1: Write failing evidence visibility and verification tests**

```java
RoleScopedEvidenceView userView = service.catalog(caseId, userActor);

assertThat(userView.items())
        .filteredOn(item -> item.visibility().equals("PRIVATE")
                && item.submittedByRole().equals("MERCHANT"))
        .allSatisfy(item -> {
            assertThat(item.contentUrl()).isNull();
            assertThat(item.redacted()).isTrue();
        });

assertThat(verification.status()).isIn(
        VERIFIED, PLAUSIBLE, SUSPICIOUS, REJECTED, NEEDS_HUMAN_REVIEW);
```

Add tests proving `REJECTED` evidence remains auditable but is excluded from the frozen dossier.

- [ ] **Step 2: Verify red**

Run:

```powershell
.\mvnw.cmd -Dtest=EvidenceRoomIntegrationTest test
```

- [ ] **Step 3: Implement deterministic and Agent-assisted checks**

Deterministic Java checks own hash, signature/source, MIME, file size, uploader, timestamps, duplicate detection, and visibility. Evidence Clerk contributes structured anomaly and consistency findings but cannot upgrade an item to `VERIFIED` without deterministic provenance.

- [ ] **Step 4: Write failing completion/deadline tests**

```java
completion.complete(caseId, USER, userActor, "user-complete-1");
completion.complete(caseId, MERCHANT, merchantActor, "merchant-complete-1");

assertThat(clockProjection.status()).isEqualTo(COMPLETED_EARLY);
assertThat(room(caseId, EVIDENCE).status()).isEqualTo(SEALED);
assertThat(room(caseId, HEARING).status()).isEqualTo(OPEN);
```

Add a Temporal test that advances virtual time by two hours with only one completion and asserts the same seal/open transition with `EXPIRED`.

- [ ] **Step 5: Implement completion signals and sealing**

`POST /api/disputes/{caseId}/evidence/complete` is idempotent per party and phase. Both confirmations signal early completion. Expiry or early completion freezes a new dossier version exactly once.

- [ ] **Step 6: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=EvidenceRoomIntegrationTest,DisputeHearingWorkflowTest test
D:\miniconda\python.exe -m pytest python-agent-service/tests/agents/test_final_agents.py -q
```

Commit:

```text
feat: enforce evidence room deadline and verification
```

---

### Task 6: Implement the three-hour, three-round court and versioned settlement

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/domain/HearingStopReason.java`
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingRoundService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/SettlementService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/api/HearingCollaborationController.java`
- Create: settlement and round entities/repositories
- Modify: `java-api-service/src/main/java/com/example/dispute/workflow/domain/HearingWorkflowCommand.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/workflow/temporal/DisputeHearingWorkflow.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/workflow/temporal/DisputeHearingWorkflowImpl.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/workflow/temporal/FulfillmentDisputeWorkflowImpl.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/workflow/temporal/DeliberationPanelWorkflowImpl.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationIntegrationTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/workflow/DisputeHearingWorkflowTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/workflow/FinalWorkflowOrchestrationTest.java`

- [ ] **Step 1: Write failing round and deadline tests**

```java
assertThat(command.hearingWaitTimeout()).isEqualTo(Duration.ofHours(3));
assertThat(command.maxHearingRounds()).isEqualTo(3);

testEnv.sleep(Duration.ofHours(3));

assertThat(result.stopReason()).isEqualTo("DEADLINE_EXPIRED");
assertThat(result.draftId()).isNotBlank();
assertThat(result.manualRequired()).isTrue();
```

Add a test that three completed rounds force `MAX_ROUNDS` without waiting for the deadline.

- [ ] **Step 2: Verify red**

Run:

```powershell
.\mvnw.cmd -Dtest=DisputeHearingWorkflowTest test
```

- [ ] **Step 3: Refactor hearing control**

The hearing Workflow starts one durable three-hour deadline when the hearing room opens. It accepts statements and supplement Signals, increments only persisted hearing rounds, never resets the deadline, and always calls C6 before returning for `MAX_ROUNDS` or `DEADLINE_EXPIRED`.

- [ ] **Step 4: Write failing settlement tests**

```java
SettlementView v1 = service.propose(caseId, merchantActor, proposal("退款50元"));
service.confirm(caseId, v1.version(), userActor);
SettlementView v2 = service.propose(caseId, merchantActor, proposal("退款60元"));

assertThat(service.get(caseId, v1.version()).status()).isEqualTo("SUPERSEDED");
assertThatThrownBy(() -> service.confirm(caseId, v1.version(), merchantActor))
        .isInstanceOf(SettlementVersionConflictException.class);

service.confirm(caseId, v2.version(), userActor);
service.confirm(caseId, v2.version(), merchantActor);
assertThat(service.get(caseId, v2.version()).status()).isEqualTo("CONFIRMED");
```

- [ ] **Step 5: Implement settlement and Workflow signal**

Only USER and MERCHANT may confirm. Confirmation of both roles on the current version signals `SETTLEMENT_CONFIRMED`; the judge produces a non-final settlement-based draft and the Workflow still creates a ReviewPacket.

- [ ] **Step 6: Write failing conditional panel tests**

```java
assertThat(policy.shouldDeliberate(lowRiskAgreedCase)).isFalse();
assertThat(policy.shouldDeliberate(highRiskCase)).isTrue();
assertThat(policy.shouldDeliberate(unsettledCase)).isTrue();
assertThat(policy.shouldDeliberate(lowConfidenceCase)).isTrue();
assertThat(policy.shouldDeliberate(majorConflictCase)).isTrue();
assertThat(policy.shouldDeliberate(ruleUncertainCase)).isTrue();
```

- [ ] **Step 7: Implement deterministic panel trigger policy**

Persist the trigger reasons. The main Workflow must skip the child panel only when all approved skip conditions are satisfied. Skipping cannot skip human review.

- [ ] **Step 8: Verify and commit**

Run:

```powershell
.\mvnw.cmd -Dtest=HearingCollaborationIntegrationTest,DisputeHearingWorkflowTest,FinalWorkflowOrchestrationTest test
```

Commit:

```text
feat: add bounded shared court and settlement
```

---

### Task 7: Extend Agent contracts for room conversations and forced convergence

**Files:**
- Modify: `python-agent-service/app/schemas/final_agents.py`
- Modify: `python-agent-service/app/agents/dispute_intake_officer.py`
- Modify: `python-agent-service/app/agents/evidence_clerk.py`
- Modify: `python-agent-service/app/agents/presiding_judge.py`
- Modify: `python-agent-service/app/agents/deliberation_panel.py`
- Modify: final prompts under `python-agent-service/app/prompts/`
- Modify: `python-agent-service/app/api/final_agents.py`
- Test: `python-agent-service/tests/agents/test_final_agents.py`
- Test: `python-agent-service/tests/agents/test_final_agent_api.py`

- [ ] **Step 1: Write failing schema tests**

```python
assert IntakeResult.model_fields.keys() >= {
    "admissible",
    "initiator_role",
    "order_reference",
    "after_sales_reference",
    "logistics_reference",
    "party_claims",
    "requested_outcome",
    "initial_risk_signals",
    "admission_recommendation",
}

forced = HearingStageRequest(
    case_id="CASE_1",
    stage="C6_DRAFT_GENERATION",
    round_no=3,
    stop_reason="DEADLINE_EXPIRED",
    deadline_expired=True,
    settlement=None,
    **valid_context,
)
assert forced.deadline_expired is True
```

- [ ] **Step 2: Verify red**

Run:

```powershell
D:\miniconda\python.exe -m pytest python-agent-service/tests/agents/test_final_agents.py -q
```

- [ ] **Step 3: Extend intake and evidence outputs**

Return room-safe utterance text separately from immutable structured results. Evidence output must include the five-level verification recommendation, deterministic evidence references, visibility warnings, and “not an authenticity guarantee.”

- [ ] **Step 4: Extend judge forced-convergence input**

C6 receives `stop_reason`, `deadline_expired`, `round_limit_reached`, latest frozen dossier version, party absence flags, and current settlement version. It must always return `non_final=true`, evidence gaps, uncertainty, reviewer attention, and a recommended draft.

- [ ] **Step 5: Keep deliberation conditional and frozen**

The panel request contains persisted trigger reasons and a frozen draft/dossier snapshot. No critic may read room messages or evidence newer than that snapshot.

- [ ] **Step 6: Verify and commit**

Run:

```powershell
D:\miniconda\python.exe -m pytest python-agent-service/tests -q
```

Expected: all Agent tests PASS.

Commit:

```text
feat: extend agents for room-based hearings
```

---

### Task 8: Converge public APIs, security, configuration, and OpenAPI

**Files:**
- Modify: controllers still mapped under `/api/v1`
- Modify: `java-api-service/src/main/java/com/example/dispute/config/SecurityConfiguration.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/config/AppProperties.java`
- Modify: `java-api-service/src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `frontend/.env.example`
- Modify: `deploy/nginx/default.conf`
- Modify: `docs/api/README.md`
- Test: `java-api-service/src/test/java/com/example/dispute/config/SecurityConfigurationTest.java`
- Test: `tests/api/test_api_contracts.py`
- Test: `tests/static/test_repository_contract.py`

- [ ] **Step 1: Add failing unversioned API and security tests**

Require these public roots and reject their old aliases:

```text
/api/disputes
/api/notifications
/api/reviews
```

Require service identity for:

```text
/internal/disputes/import
/internal/agents/**
/internal/evidence/**
```

Verify USER cannot read merchant `PRIVATE` evidence, USER/MERCHANT cannot query review copilot, and PLATFORM_REVIEWER cannot post party statements.

- [ ] **Step 2: Verify red**

Run:

```powershell
.\mvnw.cmd -Dtest=SecurityConfigurationTest test
D:\miniconda\python.exe -m pytest ..\tests\api\test_api_contracts.py -q
```

- [ ] **Step 3: Remove remaining `/api/v1` production mappings**

Update Java callers and tests in the same commit. Do not leave dual public routes. Internal service routes remain under `/internal`.

- [ ] **Step 4: Add typed configuration**

Bind:

```yaml
dispute:
  evidence-window: ${EVIDENCE_WINDOW:PT2H}
  hearing-window: ${HEARING_WINDOW:PT3H}
  max-hearing-rounds: ${MAX_HEARING_ROUNDS:3}
  sse-heartbeat: ${SSE_HEARTBEAT:PT15S}
  seed-demo-disputes: ${SEED_DEMO_DISPUTES:true}
```

Reject non-positive durations and rounds outside `1..5`.

- [ ] **Step 5: Update Nginx and OpenAPI**

Proxy SSE with buffering disabled:

```nginx
location ~ ^/api/disputes/.*/events$ {
    proxy_pass http://java-api-service:8080;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 4h;
}
```

Do not expose internal routes through Nginx.

- [ ] **Step 6: Verify and commit**

Run:

```powershell
.\mvnw.cmd test
D:\miniconda\python.exe -m pytest ..\tests\api ..\tests\static -q
docker compose config --quiet
```

Commit:

```text
refactor: converge room APIs and security
```

---

### Task 9: Generate and approve the page-level Figma system

**Files:**
- Modify: Figma file `AI Native 履约争端审理系统 — Light Cognitive Field`
- Create: `docs/design/figma-room-screen-map.md`
- Create: `docs/design/figma-prompts/overview.md`
- Create: `docs/design/figma-prompts/intake-room.md`
- Create: `docs/design/figma-prompts/evidence-room.md`
- Create: `docs/design/figma-prompts/hearing-court.md`
- Create: `docs/design/figma-prompts/review-room.md`
- Create: `docs/design/figma-prompts/outcome-and-inbox.md`

- [ ] **Step 1: Copy the approved page prompts from `Project Plan`**

Each prompt must include:

```text
Product: AI Native fulfillment-dispute hearing system
Style: bright, playful, cartoon court, warm white + sky blue + coral + mint + soft purple
Digital human: 2D character with IDLE/LISTENING/THINKING/SPEAKING/COMPLETED/HANDOFF/ERROR
Safety: AI is non-final; human reviewer owns the final gavel
Forbidden: dark black-gold style, traditional ecommerce admin dashboard, dense generic tables
```

- [ ] **Step 2: Design the overview first**

Create desktop and mobile frames with:

- left dispute-order rail;
- right hearing-adventure map;
- current room, next action, deadline, risk and party progress;
- global summons mailbox;
- seeded cases in multiple states.

Validate with a Figma screenshot before creating later pages.

- [ ] **Step 3: Design intake and evidence rooms**

Create role variants for USER and MERCHANT. The evidence room must visibly separate shared evidence directory from permission-scoped originals and include the PT2H clock, completion controls, verification states, and timeout state.

- [ ] **Step 4: Design hearing and review**

Create:

- USER/MERCHANT shared-court variant without Review Copilot;
- PLATFORM_REVIEWER read-only hearing variant;
- ReviewPacket-ready reviewer variant with Review Copilot and final actions;
- panel-present and panel-skipped states;
- settlement pending and double-confirmed states;
- PT3H expiry/forced-draft state.

- [ ] **Step 5: Design outcome and inbox**

Include unread/read summons, deep links, final decision, action execution progress, and audit trail.

- [ ] **Step 6: Inspect exact nodes**

For each approved frame:

1. fetch design context;
2. fetch metadata only if context is too large;
3. take a screenshot;
4. record file key, node id, route, role, viewport and component mapping in `figma-room-screen-map.md`.

- [ ] **Step 7: Commit prompt and node map documentation**

Commit:

```text
design: define room-based Figma screen system
```

---

### Task 10: Build the Vue shell, stores, SSE, inbox, and digital-human components

**Files:**
- Modify: `frontend/src/router/index.js`
- Modify: `frontend/src/api/client.js`
- Create: `frontend/src/api/rooms.js`
- Create: `frontend/src/api/notifications.js`
- Modify: `frontend/src/api/disputes.js`
- Modify: `frontend/src/api/evidence.js`
- Modify: `frontend/src/api/hearing.js`
- Create: `frontend/src/stores/room.js`
- Create: `frontend/src/stores/notification.js`
- Modify: existing dispute/evidence/hearing/review/agentRun stores
- Create: `frontend/src/components/avatar/DigitalHuman.vue`
- Create: `frontend/src/components/room/RoomShell.vue`
- Create: `frontend/src/components/room/PhaseCountdown.vue`
- Create: `frontend/src/components/room/ConversationStream.vue`
- Create: `frontend/src/components/notification/SummonsMailbox.vue`
- Test: matching `*.test.js` files

- [ ] **Step 1: Rewrite failing route tests**

```js
expect(paths).toEqual(expect.arrayContaining([
  "/disputes",
  "/disputes/:caseId/intake",
  "/disputes/:caseId/evidence",
  "/disputes/:caseId/hearing",
  "/disputes/:caseId/outcome",
  "/reviews",
  "/reviews/:reviewId",
]));
expect(paths).not.toContain("/disputes/:caseId/deliberation");
```

- [ ] **Step 2: Verify red**

Run:

```powershell
C:\Users\Jupiter\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\pnpm.cmd --dir frontend test
```

- [ ] **Step 3: Implement role-aware routing and API clients**

The route guard checks `meta.roles`. The frontend calls only Java public APIs. Add `EventSource` reconnection that supplies the last event id through the supported query/header bridge and reloads the room snapshot before applying replayed events.

- [ ] **Step 4: Write and implement the server-authoritative countdown**

Test:

```js
expect(wrapper.text()).toContain("01:59:59");
await wrapper.setProps({ serverNow: "2026-07-03T10:00:10Z" });
expect(wrapper.emitted("expired")).toBeUndefined();
```

The component may display zero but must never trigger a business transition; it waits for the server event.

- [ ] **Step 5: Write and implement digital-human states**

Render all seven shared states, role name, non-final notice, reduced-motion fallback, and accessible live-region text. Use Figma SVG assets; do not add an icon package.

- [ ] **Step 6: Write and implement summons inbox**

Cover unread count, read action, deep-link navigation, role-filtered content, empty/error states, and idempotent event updates.

- [ ] **Step 7: Verify and commit**

Run frontend tests and production build.

Commit:

```text
feat: add room shell and digital human frontend
```

---

### Task 11: Implement overview, intake, and evidence pages from approved Figma nodes

**Files:**
- Create: `frontend/src/views/disputes/DisputeOverviewView.vue`
- Create: `frontend/src/views/disputes/IntakeRoomView.vue`
- Create: `frontend/src/views/disputes/EvidenceRoomView.vue`
- Create: overview/intake/evidence child components
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/styles.css`
- Test: view and component tests

- [ ] **Step 1: Write failing overview interaction tests**

Assert:

```js
await wrapper.find('[data-case-id="CASE_EXT_001"]').trigger("click");
expect(wrapper.find("[data-hearing-adventure]").text()).toContain("证据书记官室");
expect(router.currentRoute.value.path).toBe("/disputes");

await wrapper.find("[data-enter-current-room]").trigger("click");
expect(router.currentRoute.value.path).toBe("/disputes/CASE_EXT_001/evidence");
```

- [ ] **Step 2: Implement the Figma-mapped overview**

Use a left dispute rail and right adventure map, not a generic table. Preserve keyboard navigation and provide list fallback at narrow widths.

- [ ] **Step 3: Write and implement intake-room tests**

Cover digital-human states, structured extracted fields, corrections, accept/not-admissible confirmation, and transition to the evidence room after the server event.

- [ ] **Step 4: Write and implement evidence-room tests**

Cover USER/MERCHANT role variants, multimodal upload, shared directory, scoped originals, five verification states, PT2H countdown, both-party completion, timeout and late-evidence messaging.

- [ ] **Step 5: Compare against Figma**

Take browser screenshots at the exact Figma desktop and mobile viewports. Correct spacing, typography, color, radius, hierarchy and overflow before proceeding.

- [ ] **Step 6: Verify and commit**

Run:

```powershell
C:\Users\Jupiter\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\pnpm.cmd --dir frontend test
C:\Users\Jupiter\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\pnpm.cmd --dir frontend build
```

Commit:

```text
feat: build dispute overview and intake rooms
```

---

### Task 12: Implement court, review, outcome, and role variants from Figma

**Files:**
- Create: `frontend/src/views/disputes/HearingCourtView.vue`
- Create: `frontend/src/views/disputes/OutcomeView.vue`
- Create: `frontend/src/views/reviews/ReviewQueueView.vue`
- Create or replace: `frontend/src/views/reviews/ReviewWorkbenchView.vue`
- Create: hearing/settlement/panel/review child components
- Test: view and role-security tests

- [ ] **Step 1: Write failing shared-court tests**

Cover evidence presentation, party seats, statements, supplement upload, round indicator, PT3H countdown, panel visibility, forced-draft status, and no Review Copilot for USER/MERCHANT.

```js
expect(userWrapper.find("[data-review-copilot]").exists()).toBe(false);
expect(userWrapper.find("[data-hearing-round]").text()).toContain("2 / 3");
expect(userWrapper.find("[data-human-final-notice]").exists()).toBe(true);
```

- [ ] **Step 2: Implement the Figma-mapped court**

Use the playful court stage, not a generic chat split pane. Messages and evidence citations remain semantically accessible and keyboard operable.

- [ ] **Step 3: Write and implement settlement tests**

Cover proposal versions, one-party confirmation, old-version invalidation, double confirmation, and read-only confirmed state.

- [ ] **Step 4: Write and implement reviewer variants**

Before ReviewPacket readiness, reviewer is read-only and sees no decision controls. After readiness, Review Copilot and final actions appear only for `PLATFORM_REVIEWER`.

- [ ] **Step 5: Implement outcome**

Render final human decision separately from AI draft, actual action records, execution status, evidence/rule references and the immutable timeline.

- [ ] **Step 6: Compare against Figma, verify, and commit**

Run frontend tests/build and browser screenshot checks for USER, MERCHANT and PLATFORM_REVIEWER.

Commit:

```text
feat: build shared court and human review experience
```

---

### Task 13: Integrate, remove legacy paths, and complete final acceptance

**Files:**
- Modify: `tests/e2e/test_main_flows.py`
- Modify: `tests/api/test_api_contracts.py`
- Modify: `tests/load/test_performance_budget.py`
- Modify: static/final acceptance tests
- Create: `docs/codex/room-based-final-acceptance-evidence.md`
- Modify: `docs/codex/formal_acceptance_report.md`
- Remove: old `/cases` frontend, old `/api/v1` production mappings, independent deliberation route, ordinary-flow packages and stale docs

- [ ] **Step 1: Add the complete E2E scenario**

The test must execute:

```text
seeded external dispute visible in overview
→ intake accepted
→ merchant receives summons
→ both parties submit evidence
→ evidence verified/scoped
→ both complete early
→ hearing opens
→ parties complete two supplement rounds
→ settlement v1 superseded by v2
→ both confirm v2
→ low-risk panel skipped
→ ReviewPacket created
→ reviewer approves
→ action executes once
→ outcome and inbox update
```

- [ ] **Step 2: Add timeout and risk scenarios**

Required independent cases:

```text
evidence PT2H expiry with one absent party
hearing PT3H expiry forces draft
three-round exhaustion forces draft
high-risk dispute triggers panel
major evidence conflict triggers panel
low-confidence draft triggers panel
not-admissible intake stops before invitation
SSE reconnect replays only authorized missing events
duplicate import/message/notification/confirmation/action remains idempotent
```

- [ ] **Step 3: Run all service-local suites**

```powershell
java-api-service\mvnw.cmd test
D:\miniconda\python.exe -m pytest python-agent-service/tests -q
D:\miniconda\python.exe -m pytest ocr-parser-service/tests -q
C:\Users\Jupiter\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\pnpm.cmd --dir frontend test
C:\Users\Jupiter\.cache\codex-runtimes\codex-primary-runtime\dependencies\bin\pnpm.cmd --dir frontend build
D:\miniconda\python.exe -m pytest tests/static tests/api tests/e2e tests/load -q
```

- [ ] **Step 4: Run runtime integration**

Validate Compose, start the isolated stack, wait for health, apply migrations, seed disputes, exercise the browser as USER/MERCHANT/PLATFORM_REVIEWER, and capture screenshots plus API/trace evidence.

- [ ] **Step 5: Run legacy/prohibited-path scan**

```powershell
rg -n "/api/v1|/cases|REGULAR_FULFILLMENT|RULE_BASED_RESOLUTION|普通履约流|短信供应商" frontend/src java-api-service/src/main python-agent-service/app "Project Plan"
```

Expected: no production path or final requirement hit. Historical migration comments are allowed only when required for replay/backfill and explicitly documented.

- [ ] **Step 6: Fill the acceptance report**

Map every applicable row to a test, command, screenshot, database query, trace, Figma node or audit record. Any veto failure means FAIL.

- [ ] **Step 7: Remediation loop**

For each failure:

1. retain or add the reproducing failing test;
2. diagnose the root cause;
3. implement the smallest safe fix;
4. rerun the focused test;
5. rerun the complete suite;
6. update acceptance evidence.

- [ ] **Step 8: Completion gate and final commit**

Completion requires:

```text
Project Plan documents consistent
V001–V012 migrate from empty PostgreSQL
all Java/Python/OCR/frontend/static/API/E2E/load tests pass
Compose runtime healthy
Figma parity checked for all approved frames and role variants
PT2H/PT3H/3-round semantics proven with virtual time
no ordinary order center or ordinary fulfillment flow
mandatory human review and deterministic execution intact
final acceptance report contains current evidence
```

Commit:

```text
test: complete room-based final acceptance
```
