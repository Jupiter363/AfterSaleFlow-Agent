# Intake Agent Dossier Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the dispute intake officer a real `app/agents` digital human workflow that uses LangGraph/LangChain to produce left-side customer-service dialogue and a right-side case-detail dossier with quality scoring and persistence.

**Architecture:** Agent orchestration lives under `python-agent-service/app/agents/dispute_intake_officer/`; harness stays generic for LLM, prompt composition, context windows, and memory. Java persists immutable room turns plus a current `case_intake_dossier` projection for the right-side board. The frontend reads the dossier projection when available and falls back to latest turn memory.

**Tech Stack:** Python FastAPI, LangGraph, LangChain, Pydantic, Spring Boot, PostgreSQL/Flyway, Vue.

---

### Task 1: Python agent workflow and dossier protocol

**Files:**
- Create: `python-agent-service/app/agents/dispute_intake_officer/__init__.py`
- Create: `python-agent-service/app/agents/dispute_intake_officer/workflow.py`
- Create: `python-agent-service/app/agents/dispute_intake_officer/schemas.py`
- Create: `python-agent-service/app/agents/dispute_intake_officer/skills/__init__.py`
- Create: `python-agent-service/app/agents/dispute_intake_officer/skills/case_detail_dossier.py`
- Modify: `python-agent-service/app/intake_turn.py`
- Modify: `python-agent-service/app/harness/prompt_composer.py`
- Modify: `python-agent-service/app/agents/prompts/dispute_intake_officer/intake_turn_dialogue.md`
- Test: `python-agent-service/tests/agents/test_intake_case_detail_dossier.py`

- [ ] Write failing tests requiring `IntakeTurnWorkflow` to be import-compatible while its implementation delegates to `app.agents.dispute_intake_officer.workflow`.
- [ ] Write failing tests requiring `scroll_snapshot.schema_version == "intake_case_detail.v1"`, a 0-100 `intake_quality.score`, and `ready_for_next_step` to become true only at score >= 80 with hard references present.
- [ ] Implement the new agent package with LangGraph nodes: `load_context`, `reason_with_llm`, `render_case_detail_dossier`, `validate_readiness`.
- [ ] Keep deterministic fallback readable Chinese only, with no mojibake.
- [ ] Run Python focused tests.

### Task 2: Java current dossier projection table

**Files:**
- Create: `java-api-service/src/main/resources/db/migration/V017__case_intake_dossier.sql`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/CaseIntakeDossierEntity.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/repository/CaseIntakeDossierRepository.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/CaseIntakeDossierView.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeAgentTurnService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomTurnMemoryView.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomTurnMemoryQueryService.java`
- Test: existing Java room/intake tests plus any compile failures.

- [ ] Write/adjust tests so latest turn memory exposes the current dossier projection.
- [ ] Add Flyway migration and JPA entity/repository.
- [ ] Persist/update current dossier projection whenever an intake agent turn succeeds.
- [ ] Keep `room_turn_memory` append-only as the historical audit trail.
- [ ] Run Java focused tests.

### Task 3: Frontend board mapping

**Files:**
- Modify: `frontend/src/views/disputes/IntakeRoomView.vue`
- Modify: `frontend/src/views/disputes/IntakeRoomView.test.js`

- [ ] Write failing test showing the right board renders `case_story`, `dispute_focus`, score, and ready-for-next-step copy.
- [ ] Map the new `case_intake_dossier` projection first, fallback to `scroll_snapshot`.
- [ ] Keep existing visual style, but make the board read like a case story expansion of the overview.
- [ ] Run frontend focused tests.

### Task 4: End-to-end verification

- [ ] Run Python focused tests.
- [ ] Run Java focused tests.
- [ ] Run frontend focused tests.
- [ ] Start local services if needed and verify in browser that a user intake message produces an LLM reply plus updated right-side case-detail dossier.
