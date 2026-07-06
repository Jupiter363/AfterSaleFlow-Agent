# Structured Hearing Court Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the backend foundation for a judge-led, three-round structured hearing room where party statements are sealed by round, the AI judge speaks after each round, the final round produces a determinate draft path, and the UI can later render judge/user/merchant/jury message lanes.

**Architecture:** Preserve the existing `hearing_round` and `hearing_round_party_submission` model as the source of round state. Add a focused court orchestration service that appends court-visible judge messages and lifecycle events after hearing opens and after each round closes; keep final adjudication C1-C6 workflow in Temporal for now. Use `AGENT_MESSAGE` with `sender_role=JUDGE`/`JURY` instead of adding fragile database enum values, so the frontend can classify speakers without a broad migration.

**Tech Stack:** Spring Boot, JPA, Flyway, PostgreSQL/Testcontainers, Java RestClient to Python Agent Service, Python FastAPI/LangChain-style Harness prompt composer, Vue hearing room later.

---

### File Structure

- Modify `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingRoundService.java`: trigger court orchestration when a round closes and when next round opens.
- Create `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtOrchestrator.java`: append judge messages and court lifecycle events idempotently.
- Create `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtAgentClient.java`: interface for the Java-to-Python round judge call.
- Create `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtAgentCommand.java`: structured request sent to the round judge.
- Create `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtAgentResult.java`: structured response used to render court messages.
- Create `java-api-service/src/main/java/com/example/dispute/workflow/infrastructure/RestClientHearingCourtAgentClient.java`: RestClient adapter to `/internal/agents/hearing/round-turn`.
- Modify `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/RoomMessageEntity.java`: expose `hearingRound` and add a create overload that can persist it.
- Modify `java-api-service/src/main/java/com/example/dispute/room/application/RoomMessageView.java`: include optional `hearingRound` for UI grouping.
- Create/modify tests in `java-api-service/src/test/java/com/example/dispute/hearing/HearingCourtOrchestratorTest.java` and `HearingCollaborationIntegrationTest.java`.
- Modify `python-agent-service/app/schemas/final_agents.py`: add `HearingRoundTurnRequest` and `HearingRoundTurnResult`.
- Create `python-agent-service/app/agents/presiding_judge/round_workflow.py`: LLM-backed structured round judge workflow using Harness prompt composition.
- Add `python-agent-service/app/agents/prompts/presiding_judge/hearing_round_turn.md`: judge round prompt.
- Modify `python-agent-service/app/harness/prompt_composer.py` and `python-agent-service/app/harness/prompt_contracts.py`: register `hearing_round_turn`.
- Modify `python-agent-service/app/main.py`: expose `/internal/agents/hearing/round-turn`.
- Add Python tests under `python-agent-service/tests` for schema, prompt composition, and round workflow fallback.

### Task 1: Java court-message persistence contract

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/RoomMessageEntity.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomMessageView.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/RoomMessageAndEventServiceTest.java`

- [ ] **Step 1: Write a failing test proving `RoomMessageView` exposes `hearingRound`**

Add a test that creates a `RoomMessageEntity` with hearing round `1`, passes it through the service view path or direct helper, and expects the returned view to expose `hearingRound=1`.

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
cd java-api-service
.\mvnw.cmd -Dtest=RoomMessageAndEventServiceTest test
```

Expected: fails because `RoomMessageView` does not yet include `hearingRound` and `RoomMessageEntity.create(...)` cannot set it.

- [ ] **Step 3: Implement the entity/view support**

Add `getHearingRound()`, a create overload with `Integer hearingRound`, and add `Integer hearingRound` to `RoomMessageView`.

- [ ] **Step 4: Re-run the focused test**

Expected: `RoomMessageAndEventServiceTest` passes.

### Task 2: Court orchestrator appends judge messages idempotently

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtOrchestrator.java`
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtAgentClient.java`
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtAgentCommand.java`
- Create: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingCourtAgentResult.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCourtOrchestratorTest.java`

- [ ] **Step 1: Write a failing test for round-1 closed behavior**

Seed a hearing case and hearing room. Stub `HearingCourtAgentClient` to return:

```json
{
  "speaker_role": "JUDGE",
  "message_text": "本轮事实陈述已封存。下一轮请用户说明签收现场情况，请商家说明物流交接记录。",
  "court_event_type": "JUDGE_NEXT_QUESTIONS_READY",
  "round_no": 1,
  "next_round_no": 2,
  "final_draft_required": false
}
```

Assert the orchestrator saves one `room_message` with `message_type=AGENT_MESSAGE`, `sender_role=JUDGE`, `hearing_round=1`, and records `JUDGE_NEXT_QUESTIONS_READY`.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```powershell
cd java-api-service
.\mvnw.cmd -Dtest=HearingCourtOrchestratorTest test
```

Expected: fails because `HearingCourtOrchestrator` does not exist.

- [ ] **Step 3: Implement the orchestrator**

The orchestrator must:

- lock/read case and hearing room;
- gather the completed round and submissions;
- call `HearingCourtAgentClient.generateRoundTurn(...)`;
- write an idempotent `RoomMessageEntity` using key `judge-round-turn:<caseId>:<roundNo>`;
- set `sender_type=AGENT`, `sender_role=JUDGE`, `sender_id=presiding-judge`;
- publish `ROOM_MESSAGE_CREATED` and a lifecycle event from the agent result;
- not duplicate messages/events on replay.

- [ ] **Step 4: Re-run the focused test**

Expected: `HearingCourtOrchestratorTest` passes.

### Task 3: HearingRoundService triggers court orchestration after round close

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingRoundService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingCollaborationIntegrationTest.java`

- [ ] **Step 1: Write a failing integration test**

When user submits round 1 and merchant submits round 1, verify `HearingCourtOrchestrator.afterRoundClosed(caseId, 1, false)` is invoked after the round is completed and before the response opens round 2.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```powershell
cd java-api-service
.\mvnw.cmd -Dtest=HearingCollaborationIntegrationTest test
```

Expected: fails because the service has no orchestrator dependency.

- [ ] **Step 3: Inject and call the orchestrator**

Call the orchestrator for:

- both-party submitted round close;
- timeout auto-submitted round close;
- trusted completeNext close.

For final round, pass `finalRound=true` so the agent can produce final-turn text and the existing Temporal final workflow can continue.

- [ ] **Step 4: Re-run the test**

Expected: focused hearing collaboration tests pass.

### Task 4: Python round judge contract and prompt

**Files:**
- Modify: `python-agent-service/app/schemas/final_agents.py`
- Create: `python-agent-service/app/agents/presiding_judge/round_workflow.py`
- Add: `python-agent-service/app/agents/prompts/presiding_judge/hearing_round_turn.md`
- Modify: `python-agent-service/app/harness/prompt_composer.py`
- Modify: `python-agent-service/app/harness/prompt_contracts.py`
- Modify: `python-agent-service/app/main.py`
- Test: Python tests for the new endpoint/workflow.

- [ ] **Step 1: Write failing Python tests**

Test that:

- prompt composer can render `hearing_round_turn`;
- the schema rejects first-person platform narration in `judge_message` only if explicitly added by future guardrails;
- `/internal/agents/hearing/round-turn` returns a valid `HearingRoundTurnResult` from a fake workflow.

- [ ] **Step 2: Run focused Python tests and verify failure**

Run:

```powershell
cd python-agent-service
python -m pytest tests -q
```

Expected: fails because schema/workflow/endpoint do not exist.

- [ ] **Step 3: Implement schema, prompt registration, workflow, and endpoint**

The prompt must use:

- Harness safety boundary;
- business code localization;
- third-person case narration;
- judge role prompt;
- trusted runtime context;
- structured output schema.

The output shape:

```json
{
  "speaker_role": "JUDGE",
  "message_text": "中文法官发言",
  "round_summary": "本轮封存摘要",
  "questions_for_user": [],
  "questions_for_merchant": [],
  "court_event_type": "JUDGE_NEXT_QUESTIONS_READY",
  "round_no": 1,
  "next_round_no": 2,
  "final_draft_required": false,
  "non_final": true,
  "requires_human_review": true
}
```

- [ ] **Step 4: Re-run focused Python tests**

Expected: tests pass.

### Task 5: Java RestClient adapter to Python round judge

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/workflow/infrastructure/RestClientHearingCourtAgentClient.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/RestClientHearingCourtAgentClientTest.java`

- [ ] **Step 1: Write a failing adapter test**

Use `MockRestServiceServer` to assert a POST to `/internal/agents/hearing/round-turn` with service headers returns a `HearingCourtAgentResult`.

- [ ] **Step 2: Run and verify failure**

Run:

```powershell
cd java-api-service
.\mvnw.cmd -Dtest=RestClientHearingCourtAgentClientTest test
```

Expected: class missing.

- [ ] **Step 3: Implement adapter**

Validate response has `speaker_role`, `message_text`, `court_event_type`, and `round_no`.

- [ ] **Step 4: Re-run adapter test**

Expected: test passes.

### Task 6: Verification and commit

**Files:**
- All changed files.

- [ ] **Step 1: Run focused Java tests**

```powershell
cd java-api-service
.\mvnw.cmd -Dtest=HearingCourtOrchestratorTest,HearingCollaborationIntegrationTest,RoomMessageAndEventServiceTest,RestClientHearingCourtAgentClientTest test
```

- [ ] **Step 2: Run focused Python tests**

```powershell
cd python-agent-service
python -m pytest tests -q
```

- [ ] **Step 3: Inspect git diff**

```powershell
git status --short
git diff --stat
```

- [ ] **Step 4: Commit and push**

```powershell
git add docs/superpowers/plans/2026-07-07-structured-hearing-court-backend.md java-api-service python-agent-service
git commit -m "feat: add structured hearing court backend"
git push
```
