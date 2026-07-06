# Agent Session Isolation Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the whole dispute system from role-level/demo isolation to explicit `actor_id` + permission-aware session isolation, so every identity owns the correct room permissions, prompt profile, memory window, event cursor, and tool/RAG namespace.

**Architecture:** Java remains the trust boundary: it resolves the authenticated actor into a server-owned `AccessSession` for authorization and then into an `AgentConversationSession` for each room/agent conversation. All room APIs, messages, events, evidence, hearing, review, and Agent calls check the `AccessSession`; Python stays stateless at process level but receives a typed `agent_context` that includes the linked access session and permission level. Frontend continues to pass `X-User-Id`/`X-Role`, but the backend never trusts frontend-provided session keys.

**Tech Stack:** Spring Boot, JPA, Flyway/PostgreSQL JSONB, FastAPI, Pydantic, LangGraph/LangChain, shared Python harness, Vue 3, Vitest, JUnit/Mockito, pytest.

---

## 0. Current-state decision

Current code already has partial isolation:

- Frontend sends `X-User-Id` and `X-Role`.
- Evidence room messages and SSE cursor are mostly scoped by current actor.
- `RoomTurnMemoryQueryService` has role-level filtering for evidence latest memory.

But this is not enough for “完全隔离”:

- `room_turn_memory.actor_id` cannot represent conversation ownership because agent replies are saved as `dispute-intake-officer` or `evidence-clerk`.
- Intake Agent commands do not carry `actor_id`.
- Intake memory is read by `case_id + room_type`, not by actor/session.
- Evidence Agent recent turns are still derived from role/turn numbers, not exact `actor_id` session.
- Python `MemeoMemoryAssembler` accepts whatever `recent_turns` Java sends; it has no `scope_key` guard.
- Prompt selection is by node name only; it cannot distinguish `agent_key + actor_role + actor_id + prompt_profile_id`.

Decision: introduce explicit Access Session and Agent Conversation Session boundaries instead of overloading existing fields.

This refactor uses two related sessions:

1. `AccessSession`: the system-wide case/room permission envelope for one actor. It answers “what is this identity allowed to read or do?”
2. `AgentConversationSession`: the private memory/prompt/tool namespace for one digital-human agent conversation. It answers “which LLM context belongs to this actor + room + agent?”

Canonical access scope:

```text
tenant_id
+ case_id
+ actor_id
+ actor_role
+ permission_level
```

Canonical agent scope:

```text
tenant_id
+ case_id
+ room_type
+ actor_id
+ actor_role
+ agent_key
+ prompt_profile_id
```

Default local `tenant_id` is `default`.

Reviewer/admin rule:

- `PLATFORM_REVIEWER`, `ADMIN`, and `SYSTEM` access sessions may pass all case read permission checks and review permission checks according to their permission level.
- Privileged sessions are audit-visible and can read sealed/frozen artifacts, room messages, evidence, hearing records, and review packets.
- Privileged sessions do not get merged into a party’s private Agent memory. If a reviewer needs a digital-human assistant, that assistant uses a separate reviewer agent session, such as `REVIEW_COPILOT`.
- `USER` and `MERCHANT` access sessions can never pass review-only actions, even if they know a case id or URL.

---

## 1. Target data model

### New table: `case_access_session`

This is the authorization session used by all rooms and components.

Fields:

- `id varchar(64) primary key`
- `tenant_id varchar(64) not null default 'default'`
- `case_id varchar(64) not null`
- `actor_id varchar(128) not null`
- `actor_role varchar(64) not null`
- `permission_level varchar(64) not null`
- `permission_scopes_json jsonb not null default '[]'::jsonb`
- `status varchar(32) not null default 'ACTIVE'`
- `created_at timestamptz not null default now()`
- `updated_at timestamptz not null default now()`
- `created_by varchar(128) not null`

Unique key:

```sql
unique (tenant_id, case_id, actor_id, actor_role, permission_level)
```

Permission levels:

| Actor role | Permission level | Main rights |
| --- | --- | --- |
| `USER` | `PARTY_USER` | Read own intake private session, participate in evidence/hearing as user, read shared party artifacts, no review actions |
| `MERCHANT` | `PARTY_MERCHANT` | Read own intake private session, participate in evidence/hearing as merchant, read shared party artifacts, no review actions |
| `CUSTOMER_SERVICE` | `SERVICE_ASSIST` | Read service-visible case flow and operate intake/service support, no final review approval |
| `PLATFORM_REVIEWER` | `REVIEWER_ALL` | Read all case rooms/artifacts and perform review actions |
| `ADMIN` | `ADMIN_ALL` | Read/administer all case rooms/artifacts and configuration |
| `SYSTEM` | `SYSTEM_ALL` | System orchestration and scheduled expiry actions |

### New table: `agent_conversation_session`

Fields:

- `id varchar(64) primary key`
- `tenant_id varchar(64) not null default 'default'`
- `case_id varchar(64) not null`
- `room_type varchar(32) not null`
- `actor_id varchar(128) not null`
- `actor_role varchar(64) not null`
- `agent_key varchar(128) not null`
- `access_session_id varchar(64) not null`
- `prompt_profile_id varchar(128) not null`
- `memory_policy_id varchar(128) not null`
- `conversation_scope varchar(512) not null`
- `status varchar(32) not null default 'ACTIVE'`
- `created_at timestamptz not null default now()`
- `updated_at timestamptz not null default now()`
- `created_by varchar(128) not null`

Unique key:

```sql
unique (tenant_id, case_id, room_type, actor_id, actor_role, agent_key, prompt_profile_id)
```

### Extend `room_turn_memory`

Add fields:

- `agent_session_id varchar(64)`
- `access_session_id varchar(64)`
- `conversation_scope varchar(512)`
- `session_actor_id varchar(128)`
- `session_actor_role varchar(64)`
- `prompt_profile_id varchar(128)`
- `memory_policy_snapshot_json jsonb not null default '{}'::jsonb`

Indexes:

```sql
create index idx_room_turn_memory_session_turn
    on room_turn_memory(agent_session_id, turn_no desc);

create index idx_room_turn_memory_scope_turn
    on room_turn_memory(conversation_scope, turn_no desc);
```

After backfill, all new writes must set these fields. If old rows cannot be safely backfilled, keep columns nullable but ensure application code refuses to read session-sensitive memory without `agent_session_id`.

### Extend `case_intake_dossier`

Add:

- `source_agent_session_id varchar(64)`
- `source_actor_id varchar(128)`
- `source_actor_role varchar(64)`

Rationale: the accepted dossier is a case-level artifact after confirmation.

### New table: `agent_session_dossier`

Use this for the right-side working board while a digital-human room conversation is still in progress.

Fields:

- `id varchar(64) primary key`
- `agent_session_id varchar(64) not null unique`
- `case_id varchar(64) not null`
- `room_type varchar(32) not null`
- `actor_id varchar(128) not null`
- `actor_role varchar(64) not null`
- `agent_key varchar(128) not null`
- `dossier_json jsonb not null default '{}'::jsonb`
- `quality_score integer not null default 0`
- `ready_for_next_step boolean not null default false`
- `recommendation varchar(64)`
- `source_turn_no integer`
- `created_at/updated_at/created_by/updated_by`

Rationale: the draft board belongs to a private Agent Session. `case_intake_dossier` should represent the accepted/frozen case-level intake handoff after confirmation, not every private drafting turn.

### Actor-aware room audience

Current room/message event visibility is mostly role-based. For full same-role isolation, add actor-aware audience support.

Preferred model:

- `room_message_audience(message_id, actor_id, actor_role)`
- `case_event_audience(event_id, actor_id, actor_role)`

Fallback if table split is too large for this refactor:

- Add `audience_actor_ids_json jsonb not null default '[]'::jsonb` beside existing `audience_json`.
- Role-based audience remains for privileged roles; party-private messages include exact actor id.

### Permission check surface

All room and case components should depend on a single permission service instead of scattered role checks.

Proposed Java service:

```java
public interface SessionPermissionService {
    CaseAccessSessionEntity resolveAccessSession(String caseId, AuthenticatedActor actor);
    void requireCaseRead(CaseAccessSessionEntity session);
    void requireRoomRead(CaseAccessSessionEntity session, RoomType roomType);
    void requirePartyPrivateSessionRead(CaseAccessSessionEntity session, String ownerActorId, ActorRole ownerRole);
    void requireEvidenceSubmit(CaseAccessSessionEntity session);
    void requireHearingParticipate(CaseAccessSessionEntity session);
    void requireReviewRead(CaseAccessSessionEntity session);
    void requireReviewDecision(CaseAccessSessionEntity session);
}
```

Every controller/application service should resolve an access session once at the boundary and pass it inward. That keeps future rooms and components aligned with the same permission vocabulary.

---

## 2. Implementation tasks

### Task 1: Add Access Session and Agent Session persistence primitives

**Files:**

- Create: `java-api-service/src/main/resources/db/migration/V019__agent_conversation_sessions.sql`
- Create: `java-api-service/src/main/java/com/example/dispute/room/domain/PermissionLevel.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/CaseAccessSessionEntity.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/repository/CaseAccessSessionRepository.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/AgentConversationSessionEntity.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/repository/AgentConversationSessionRepository.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/AgentSessionDossierEntity.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/repository/AgentSessionDossierRepository.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/AccessSessionResolver.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/SessionPermissionService.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/AgentSessionKey.java`
- Create: `java-api-service/src/main/java/com/example/dispute/room/application/AgentSessionResolver.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/RoomTurnMemoryEntity.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/repository/RoomTurnMemoryRepository.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/AccessSessionResolverTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/SessionPermissionServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/AgentConversationSessionResolverTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/AgentSessionDossierPersistenceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/RoomTurnMemoryPersistenceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/database/MigrationIntegrationTest.java`

- [ ] **Step 1: Write failing access session and permission tests**

  Add tests proving:

  - USER resolves to permission level `PARTY_USER`.
  - MERCHANT resolves to permission level `PARTY_MERCHANT`.
  - PLATFORM_REVIEWER resolves to permission level `REVIEWER_ALL`.
  - ADMIN resolves to permission level `ADMIN_ALL`.
  - USER/MERCHANT pass `requireCaseRead` only for cases where they are owner/participant.
  - USER/MERCHANT fail `requireReviewRead` and `requireReviewDecision`.
  - PLATFORM_REVIEWER passes `requireCaseRead`, `requireRoomRead`, `requireReviewRead`, and `requireReviewDecision`.

  Run:

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=AccessSessionResolverTest,SessionPermissionServiceTest" test
  ```

  Expected: fails because access session primitives do not exist.

- [ ] **Step 2: Write failing agent session resolver tests**

  Add tests proving:

  - Resolving the same `case_id + room_type + actor_id + actor_role + agent_key + prompt_profile_id` twice returns the same session.
  - Two different `actor_id` values with the same role produce different sessions.
  - Two different `agent_key` values for the same actor produce different sessions.
  - Agent sessions store `access_session_id`.
  - `conversation_scope` is deterministic and includes no frontend-supplied raw session id.

  Run:

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=AgentConversationSessionResolverTest" test
  ```

  Expected: fails because `AgentSessionResolver` does not exist.

- [ ] **Step 3: Write failing persistence tests**

  Extend `RoomTurnMemoryPersistenceTest` to save participant and agent rows with the same `agent_session_id`, then query by `agent_session_id` and confirm only those rows return.

  Run:

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=RoomTurnMemoryPersistenceTest" test
  ```

  Expected: fails because the new columns/repository query do not exist.

- [ ] **Step 4: Implement Flyway migration**

  `V019__agent_conversation_sessions.sql` must:

  - Create `case_access_session`.
  - Create `agent_conversation_session`.
  - Create `agent_session_dossier`.
  - Alter `room_turn_memory` with session fields.
  - Alter `case_intake_dossier` with source session fields.
  - Add actor-aware audience support either through audience tables or `audience_actor_ids_json`.
  - Backfill best-effort sessions:
    - Intake participant/agent rows use case initiator id/role when available.
    - Evidence participant rows use their own `actor_id`/`answer_role`.
    - Evidence agent rows use the participant row with the same `case_id + room_type + turn_no`; if ambiguous, leave nullable and unreadable by session APIs.
  - Add indexes listed in the target data model.

- [ ] **Step 5: Implement entity/repository/resolver**

  `AccessSessionResolver` should expose:

  ```java
  public CaseAccessSessionEntity resolve(String caseId, AuthenticatedActor actor)
  ```

  It should derive `PermissionLevel` server-side from actor role and case relationship:

  - `USER` -> `PARTY_USER`
  - `MERCHANT` -> `PARTY_MERCHANT`
  - `CUSTOMER_SERVICE` -> `SERVICE_ASSIST`
  - `PLATFORM_REVIEWER` -> `REVIEWER_ALL`
  - `ADMIN` -> `ADMIN_ALL`
  - `SYSTEM` -> `SYSTEM_ALL`

  `SessionPermissionService` should expose the methods defined in the permission surface section and be the only class that answers review/evidence/hearing authorization questions for new code.

  `AgentSessionResolver` should expose:

  ```java
  public AgentConversationSessionEntity resolve(
      CaseAccessSessionEntity accessSession,
      String caseId,
      RoomType roomType,
      AuthenticatedActor actor,
      String agentKey,
      String promptProfileId,
      String memoryPolicyId
  )
  ```

  It should build `conversation_scope` server-side:

  ```text
  default:{caseId}:{roomType}:{actorId}:{actorRole}:{agentKey}:{promptProfileId}:{accessSessionId}
  ```

  It should use repository lookup by unique fields and create when absent.

- [ ] **Step 6: Add session-aware repository methods**

  Add:

  ```java
  List<RoomTurnMemoryEntity> findTop10ByAgentSessionIdOrderByTurnNoDesc(String agentSessionId);
  List<RoomTurnMemoryEntity> findTop50ByAgentSessionIdOrderByTurnNoDesc(String agentSessionId);
  Optional<RoomTurnMemoryEntity> findTopByAgentSessionIdAndAgentRoleIsNotNullOrderByTurnNoDesc(String agentSessionId);
  @Query("select coalesce(max(memory.turnNo), 0) from RoomTurnMemoryEntity memory where memory.agentSessionId = :agentSessionId")
  int findMaxTurnNoByAgentSessionId(String agentSessionId);
  ```

- [ ] **Step 7: Extend migration integration assertions**

  `MigrationIntegrationTest` should assert:

  - `agent_conversation_session` exists.
  - `case_access_session` exists.
  - `agent_session_dossier` exists.
  - `room_turn_memory.agent_session_id` exists.
  - Session indexes exist.
  - Actor-aware audience storage exists.

- [ ] **Step 8: Run focused tests**

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=AccessSessionResolverTest,SessionPermissionServiceTest,AgentConversationSessionResolverTest,AgentSessionDossierPersistenceTest,RoomTurnMemoryPersistenceTest,MigrationIntegrationTest" test
  ```

  Expected: pass.

---

### Task 2: Refactor intake room to exact initiator session isolation

**Files:**

- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeAgentTurnCommand.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeAgentTurnService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeRecentTurn.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomTurnMemoryQueryService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/CaseIntakeDossierEntity.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/repository/CaseIntakeDossierRepository.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeRoomService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/IntakeAgentTurnServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/IntakeRoomServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/RoomTurnMemoryQueryServiceTest.java`

- [ ] **Step 1: Write failing test for actor-specific intake command**

  In `IntakeAgentTurnServiceTest`, assert the captured `IntakeAgentTurnCommand` contains:

  - `agent_context.actor_id == actor.actorId()`
  - `agent_context.actor_role == actor.role().name()`
  - `agent_context.agent_key == "DISPUTE_INTAKE_OFFICER"`
  - `agent_context.agent_session_id` is non-blank
  - `recent_turns` excludes rows from a different `agent_session_id`

  Run:

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=IntakeAgentTurnServiceTest" test
  ```

  Expected: fails because `agent_context` is not present.

- [ ] **Step 2: Write failing latest-memory leak test**

  In `RoomTurnMemoryQueryServiceTest`, create two intake sessions for two actors on the same case. Save a later agent memory for actor B and an earlier one for actor A. Query latest as actor A and assert actor A receives actor A’s memory, not actor B’s later memory.

  Expected: fails because current intake latest query is case-level.

- [ ] **Step 3: Add `AgentInvocationContext` to Java command**

  Add a nested record or shared record:

  ```java
  public record AgentInvocationContext(
      @JsonProperty("tenant_id") String tenantId,
      @JsonProperty("case_id") String caseId,
      @JsonProperty("room_type") RoomType roomType,
      @JsonProperty("actor_id") String actorId,
      @JsonProperty("actor_role") String actorRole,
      @JsonProperty("access_session_id") String accessSessionId,
      @JsonProperty("permission_level") String permissionLevel,
      @JsonProperty("permission_scopes") List<String> permissionScopes,
      @JsonProperty("agent_key") String agentKey,
      @JsonProperty("agent_invocation_id") String agentInvocationId,
      @JsonProperty("agent_session_id") String agentSessionId,
      @JsonProperty("conversation_scope") String conversationScope,
      @JsonProperty("scope_type") String scopeType,
      @JsonProperty("allowed_actor_ids") List<String> allowedActorIds,
      @JsonProperty("allowed_actor_roles") List<String> allowedActorRoles,
      @JsonProperty("prompt_profile_id") String promptProfileId,
      @JsonProperty("memory_policy_id") String memoryPolicyId
  ) {}
  ```

  Prefer a shared file: `java-api-service/src/main/java/com/example/dispute/room/application/AgentInvocationContext.java`.

- [ ] **Step 4: Resolve session before each intake Agent call**

  In `IntakeAgentTurnService`:

  - Resolve session with `agentKey=DISPUTE_INTAKE_OFFICER`.
  - Replace `findMaxTurnNo(caseId, INTAKE)` with `findMaxTurnNoByAgentSessionId(session.id)`.
  - Replace `latestScrollSnapshot(caseId)` with `latestScrollSnapshot(agentSessionId)`.
  - Replace `recentTurns(caseId)` with `recentTurns(agentSessionId)`.
  - Save participant and agent memory rows with the same `agent_session_id`, `conversation_scope`, `session_actor_id`, `session_actor_role`, and `prompt_profile_id`.
  - Keep `actor_id` on participant rows as the human sender; keep `actor_id` on agent rows as the agent identity for audit, but do not use it for session scoping.

- [ ] **Step 5: Write working board to `agent_session_dossier`**

  When `upsertCurrentDossier` receives a scroll snapshot, write it to `agent_session_dossier` keyed by `agent_session_id`, not directly to the case-level accepted dossier.

- [ ] **Step 6: Freeze accepted dossier on confirmation**

  In `IntakeRoomService`, when the initiator clicks “确认发起并上报”:

  - Read the current actor’s `agent_session_dossier`.
  - Copy its `dossier_json` into `case_intake_dossier`.
  - Set:

  - `source_agent_session_id`
  - `source_actor_id`
  - `source_actor_role`

  Evidence room may read the accepted dossier case-wide after intake confirmation. Private chat history must not be read case-wide.

- [ ] **Step 7: Run focused Java tests**

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=IntakeAgentTurnServiceTest,IntakeRoomServiceTest,RoomTurnMemoryQueryServiceTest" test
  ```

  Expected: pass.

---

### Task 3: Refactor evidence room to exact party session isolation

**Files:**

- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/EvidenceAgentTurnCommand.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/EvidenceAgentTurnService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomTurnMemoryQueryService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceCatalogService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/EvidenceAgentTurnServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/RoomTurnMemoryQueryServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/evidence/EvidenceVerificationAndCatalogServiceTest.java`

- [ ] **Step 1: Write failing same-role isolation test**

  In `EvidenceAgentTurnServiceTest`, create two actors with role `USER` on the same case and two different sessions. Save previous turns for both. Send a new evidence message as user A and assert command `recent_turns` includes only user A’s session history.

  Expected: fails because current code derives scope from role/turn number.

- [ ] **Step 2: Write failing latest evidence memory test**

  In `RoomTurnMemoryQueryServiceTest`, create evidence sessions for user and merchant. Save a later merchant clerk reply. Query latest as user and assert the user does not receive the merchant reply.

  Expected: current role-level test may pass for USER/MERCHANT, but same-role multi-user test should fail until session filtering exists.

- [ ] **Step 3: Decide private evidence catalog policy**

  Default policy for this refactor:

  - `PRIVATE`: visible to submitting actor and platform roles only.
  - `PARTIES`: visible to both user and merchant.
  - `PLATFORM`: visible to platform roles only.

  If current product intent is to reveal redacted private metadata to the counterparty, document it explicitly and do not change this behavior in the same task. Otherwise, add failing tests proving counterparty cannot see private item id/status/existence.

- [ ] **Step 4: Resolve Evidence Clerk session before Agent call**

  In `EvidenceAgentTurnService`:

  - Resolve session with `agentKey=EVIDENCE_CLERK`.
  - Use `findMaxTurnNoByAgentSessionId`.
  - Save participant and agent memory rows under that session.
  - Build `EvidenceAgentTurnCommand.agent_context`.
  - Replace `recentTurns(caseId, actor.role())` with `recentTurns(agentSessionId)`.

- [ ] **Step 5: Update latest memory query**

  `RoomTurnMemoryQueryService.latestAgentMemory` should:

  - For `INTAKE` and party actor: resolve/read the actor’s intake session.
  - For `EVIDENCE` and party actor: resolve/read the actor’s evidence session.
  - For platform/reviewer/admin: allow case-level latest only when the UI explicitly requests platform mode; otherwise use session when available.

- [ ] **Step 6: Run focused Java tests**

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=EvidenceAgentTurnServiceTest,RoomTurnMemoryQueryServiceTest,EvidenceVerificationAndCatalogServiceTest" test
  ```

  Expected: pass.

---

### Task 4: Upgrade room messages and event replay to actor-aware audience

**Files:**

- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomMessageService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/CaseEventService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/RoomMessageEntity.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/entity/CaseTimelineEventEntity.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/repository/RoomMessageRepository.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/infrastructure/persistence/repository/CaseTimelineEventRepository.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/RoomMessageAndEventServiceTest.java`

- [ ] **Step 1: Write failing same-role message leak test**

  Create two actors with role `USER` in the same evidence room. Save a party-private message for user A. List messages as user B and assert user B cannot see it.

- [ ] **Step 2: Write failing same-role event replay leak test**

  Record a room message event targeted to user A. Replay/catch up events as user B and assert user B does not receive that event.

- [ ] **Step 3: Implement actor-aware audience**

  Use the storage selected in Task 1:

  - Party-private messages/events include exact actor id.
  - Shared room events include both case party actor ids.
  - Privileged roles keep role-based override.
  - Idempotency keys for private agent replies include `agent_session_id`.

- [ ] **Step 4: Run focused Java tests**

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=RoomMessageAndEventServiceTest" test
  ```

  Expected: pass.

---

### Task 5: Add Python `AgentInvocationContext` and memory scope guards

**Files:**

- Create: `python-agent-service/app/harness/invocation_context.py`
- Modify: `python-agent-service/app/schemas/final_agents.py`
- Modify: `python-agent-service/app/harness/memory.py`
- Modify: `python-agent-service/app/agents/dispute_intake_officer/workflow.py`
- Modify: `python-agent-service/app/agents/evidence_clerk/workflow.py`
- Test: `python-agent-service/tests/harness/test_agent_invocation_context.py`
- Test: `python-agent-service/tests/harness/test_memeo_memory.py`
- Test: `python-agent-service/tests/agents/test_intake_turn.py`
- Test: `python-agent-service/tests/agents/test_evidence_clerk_turn.py`

- [ ] **Step 1: Write failing schema tests**

  Add tests proving:

  - `IntakeTurnRequest` requires `agent_context`.
  - `EvidenceTurnRequest` requires `agent_context`.
  - `agent_context.actor_id` matches top-level evidence `actor_id` when both are present.
  - Invalid or blank `agent_session_id` is rejected.

  Run:

  ```powershell
  cd python-agent-service
  pytest tests/harness/test_agent_invocation_context.py tests/agents/test_intake_turn.py tests/agents/test_evidence_clerk_turn.py -q
  ```

  Expected: fails before schema exists.

- [ ] **Step 2: Implement `AgentInvocationContext`**

  Model fields:

  ```python
  class AgentInvocationContext(StrictModel):
      tenant_id: Identifier = "default"
      case_id: Identifier
      room_type: Literal["INTAKE", "EVIDENCE", "HEARING", "REVIEW"]
      actor_id: Identifier
      actor_role: Identifier
      access_session_id: Identifier
      permission_level: Literal[
          "PARTY_USER",
          "PARTY_MERCHANT",
          "SERVICE_ASSIST",
          "REVIEWER_ALL",
          "ADMIN_ALL",
          "SYSTEM_ALL",
      ]
      permission_scopes: list[Identifier] = Field(default_factory=list)
      agent_key: Identifier
      agent_invocation_id: Identifier
      agent_session_id: Identifier
      conversation_scope: str = Field(min_length=10, max_length=512)
      scope_type: Literal[
          "INTAKE_INITIATOR_PRIVATE",
          "EVIDENCE_PARTY_PRIVATE",
          "ROOM_SHARED",
          "SYSTEM",
      ]
      allowed_actor_ids: list[Identifier] = Field(default_factory=list)
      allowed_actor_roles: list[Identifier] = Field(default_factory=list)
      prompt_profile_id: Identifier
      memory_policy_id: Identifier
  ```

  Add cross-field validators in request schemas:

  - `request.case_id == agent_context.case_id`
  - `request.room_type == agent_context.room_type`
  - evidence `actor_id == agent_context.actor_id`
  - evidence `actor_role == agent_context.actor_role`

- [ ] **Step 3: Write failing memory guard tests**

  Extend `test_memeo_memory.py`:

  - When `expected_agent_session_id="SESSION_A"`, turns with `agent_session_id="SESSION_B"` are ignored or rejected.
  - Preferred behavior: strict rejection with `ValueError` for backend contract violations.
  - Final behavior: raise a named `MemoryScopeViolation` so API errors and logs are distinguishable from ordinary validation errors.
  - Turns without `agent_session_id` are rejected when `strict_scope=True`.

- [ ] **Step 4: Implement memory scope guard**

  Update:

  ```python
  MemeoMemoryAssembler().assemble(
      recent_turns,
      expected_agent_session_id=ctx.agent_session_id,
      expected_conversation_scope=ctx.conversation_scope,
      strict_scope=True,
  )
  ```

  `prompt_memory` must only be built from accepted turns.

  In strict mode, do not silently skip polluted memory. Fail closed:

  ```python
  class MemoryScopeViolation(ValueError):
      pass
  ```

  The API layer may convert this to a governed 422 response or a degraded empty-memory run, but the violation must be visible in logs/tracing.

- [ ] **Step 5: Pass context into workflows**

  Intake and evidence workflows should:

  - Read `request["agent_context"]`.
  - Include safe context fields in `case_data`: `case_id`, `room_type`, `actor_role`, `agent_key`, `prompt_profile_id`.
  - Not include cross-party private data.
  - Build memory frame using strict session guard.
  - Preserve `agent_invocation_id` in logs/traces for a single LLM turn.

- [ ] **Step 6: Run focused Python tests**

  ```powershell
  cd python-agent-service
  pytest tests/harness/test_agent_invocation_context.py tests/harness/test_memeo_memory.py tests/agents/test_intake_turn.py tests/agents/test_evidence_clerk_turn.py -q
  ```

  Expected: pass.

---

### Task 6: Add prompt-profile selection hooks in harness

**Files:**

- Modify: `python-agent-service/app/harness/prompt_composer.py`
- Modify: `python-agent-service/app/harness/model_runner.py`
- Create: `python-agent-service/app/agents/prompts/dispute_intake_officer/intake_turn_case_detail.user.md`
- Create: `python-agent-service/app/agents/prompts/dispute_intake_officer/intake_turn_case_detail.merchant.md`
- Create: `python-agent-service/app/agents/prompts/evidence_clerk/evidence_turn.user.md`
- Create: `python-agent-service/app/agents/prompts/evidence_clerk/evidence_turn.merchant.md`
- Test: `python-agent-service/tests/harness/test_prompt_composer.py`
- Test: `python-agent-service/tests/harness/test_model_runner.py`

- [ ] **Step 1: Write failing prompt selection tests**

  Prove:

  - `PromptRepository.template_path("evidence_turn", prompt_profile_id="EVIDENCE_CLERK:USER:v1")` resolves the user variant.
  - Merchant profile resolves merchant variant.
  - Unknown profile falls back to the base node template only when explicitly allowed.

- [ ] **Step 2: Add `prompt_profile_id` to model runner**

  `HarnessModelRunner.invoke_structured` should accept optional `agent_context` or `prompt_profile_id` and pass it into the prompt repository.

  The trusted session envelope must be generated by harness, not by untrusted `case_data`. Separate the prompt into:

  - trusted system/developer envelope: `agent_key`, `actor_id`, `actor_role`, `agent_session_id`, `scope_type`, `allowed_actor_ids`, `prompt_profile_id`
  - untrusted case payload: case facts, message text, evidence summaries, memory text

- [ ] **Step 3: Add role-specific prompt files**

  Keep variants small:

  - Shared base rules remain in existing prompt.
  - User/Merchant files only specialize tone, checklist wording, and evidence examples.
  - Do not add liability/remedy judgment to evidence prompt.

- [ ] **Step 4: Run focused tests**

  ```powershell
  cd python-agent-service
  pytest tests/harness/test_prompt_composer.py tests/harness/test_model_runner.py -q
  ```

  Expected: pass.

---

### Task 7: Frontend session hygiene and actor-switch safety

**Files:**

- Modify: `frontend/src/state/actor.js`
- Modify: `frontend/src/stores/room.js`
- Modify: `frontend/src/views/disputes/IntakeRoomView.vue`
- Modify: `frontend/src/views/disputes/EvidenceRoomView.vue`
- Test: `frontend/src/stores/room.test.js`
- Test: `frontend/src/views/disputes/IntakeRoomView.test.js`
- Test: `frontend/src/views/disputes/EvidenceRoomView.test.js`

- [ ] **Step 1: Write failing tests for actor switch clearing**

  In evidence and intake views:

  - Mount as actor A.
  - Load messages.
  - Switch to actor B.
  - Assert messages clear immediately before new request resolves.
  - Assert stale actor A refresh cannot overwrite actor B view.

- [ ] **Step 2: Write failing cursor isolation tests**

  `roomStore` should persist durable cursors by:

  ```text
  case_id + room_type + actor_id + actor_role
  ```

  Existing evidence cursor logic is close; ensure intake room uses the same pattern.

- [ ] **Step 3: Implement UI hygiene**

  - On actor identity change, increment generation token.
  - Abort existing SSE stream.
  - Clear local messages/catalog/session-derived panels immediately.
  - Start new stream with new actor.
  - Never submit a frontend-invented `agent_session_id`; it is server-owned.
  - If backend later returns a read-only `agent_session_id`, frontend may use it only as a local workspace/cursor key.

  Intake specifically must gain the same stale-response guard that evidence already has:

  - `workspaceGeneration`
  - `actor/case snapshot`
  - stale response ignore
  - actor/case/session change reset

- [ ] **Step 4: Keep backend as authority**

  Frontend may compute a display key for remounting, but must not send `agent_session_id` in public APIs. It only sends `X-User-Id` and `X-Role`.

- [ ] **Step 5: Run focused frontend tests**

  ```powershell
  cd frontend
  pnpm vitest run src/stores/room.test.js src/views/disputes/IntakeRoomView.test.js src/views/disputes/EvidenceRoomView.test.js
  ```

  Expected: pass.

---

### Task 8: Route all current room and case permissions through Access Session

**Files:**

- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomMessageService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/RoomTurnMemoryQueryService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/IntakeRoomService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceApplicationService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/evidence/application/EvidenceCatalogService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/room/application/EvidenceCompletionService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/hearing/application/HearingApplicationService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/review/application/ReviewApplicationService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/outcome/application/CaseOutcomeService.java`
- Modify: `java-api-service/src/main/java/com/example/dispute/notification/application/NotificationApplicationService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/SessionPermissionServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/room/RoomMessageAndEventServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/evidence/EvidenceVerificationAndCatalogServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/hearing/HearingApplicationServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/review/ReviewApplicationServiceTest.java`
- Test: `java-api-service/src/test/java/com/example/dispute/outcome/CaseOutcomeServiceTest.java`

- [ ] **Step 1: Write failing cross-room permission matrix tests**

  Prove these rules:

  - USER can read own case overview, own intake private session, shared evidence, own/private evidence, hearing participation UI, and final outcome.
  - MERCHANT can read own case overview, own intake private session if they initiated, shared evidence, own/private evidence, hearing participation UI, and final outcome.
  - USER cannot read merchant-private intake/evidence clerk chat.
  - MERCHANT cannot read user-private intake/evidence clerk chat.
  - USER and MERCHANT fail review queue, review packet, review decision, reviewer copilot, and approval APIs.
  - PLATFORM_REVIEWER can read all rooms/artifacts for the case and can perform review decision APIs.
  - PLATFORM_REVIEWER does not write into USER/MERCHANT private Agent memory; reviewer assistant uses reviewer agent session.

  Run:

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=SessionPermissionServiceTest,RoomMessageAndEventServiceTest,EvidenceVerificationAndCatalogServiceTest,HearingApplicationServiceTest,ReviewApplicationServiceTest,CaseOutcomeServiceTest" test
  ```

  Expected: fails until services depend on `SessionPermissionService`.

- [ ] **Step 2: Replace scattered role checks with access session checks**

  For each service:

  - Resolve `CaseAccessSessionEntity` once from `caseId + AuthenticatedActor`.
  - Use `SessionPermissionService` for read/write/review checks.
  - Keep privileged read override only through permission level, not raw role comparison.
  - Keep party-private checks actor-specific.

- [ ] **Step 3: Add session id to audit metadata**

  For mutations and review decisions, persist or log:

  - `access_session_id`
  - `permission_level`
  - `actor_id`
  - `actor_role`

  This can be added to JSON audit fields where schema columns do not yet exist. Do not block the refactor on adding audit columns to every table.

- [ ] **Step 4: Run focused Java tests**

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=SessionPermissionServiceTest,RoomMessageAndEventServiceTest,EvidenceVerificationAndCatalogServiceTest,HearingApplicationServiceTest,ReviewApplicationServiceTest,CaseOutcomeServiceTest" test
  ```

  Expected: pass.

---

### Task 9: Contract and integration regression

**Files:**

- Modify: files from Tasks 1-7 only when the focused regression command identifies a concrete failing contract.
- Test: Java room tests, Python harness/agent tests, frontend room tests.

- [ ] **Step 1: Run Java focused suite**

  ```powershell
  ./mvnw -q -pl java-api-service "-Dtest=AccessSessionResolverTest,SessionPermissionServiceTest,AgentConversationSessionResolverTest,AgentSessionDossierPersistenceTest,RoomTurnMemoryPersistenceTest,IntakeAgentTurnServiceTest,IntakeRoomServiceTest,EvidenceAgentTurnServiceTest,RoomTurnMemoryQueryServiceTest,RoomMessageAndEventServiceTest,EvidenceVerificationAndCatalogServiceTest,HearingApplicationServiceTest,ReviewApplicationServiceTest,CaseOutcomeServiceTest,MigrationIntegrationTest" test
  ```

- [ ] **Step 2: Run Python focused suite**

  ```powershell
  cd python-agent-service
  pytest tests/harness/test_agent_invocation_context.py tests/harness/test_memeo_memory.py tests/harness/test_prompt_composer.py tests/harness/test_model_runner.py tests/agents/test_intake_turn.py tests/agents/test_evidence_clerk_turn.py -q
  ```

- [ ] **Step 3: Run frontend focused suite**

  ```powershell
  cd frontend
  pnpm vitest run src/stores/room.test.js src/views/disputes/IntakeRoomView.test.js src/views/disputes/EvidenceRoomView.test.js
  ```

- [ ] **Step 4: Start local dev services**

  Follow current project convention:

  - Spring Boot local.
  - Python Agent local.
  - Frontend Vite local.
  - MySQL/PostgreSQL/Redis/Milvus/MinIO through Docker Compose as currently configured.

- [ ] **Step 5: Browser E2E verification**

  In browser, verify:

  - Actor USER enters intake room and chats with Intake Officer.
  - Switch actor to MERCHANT / receiver and enter same intake URL: left chat is locked; private intake history is not visible.
  - USER confirms intake and enters evidence room.
  - USER sends evidence clerk message; gets LLM-backed reply.
  - Switch to MERCHANT; evidence clerk chat is empty or merchant-only, not USER history.
  - MERCHANT sends evidence clerk message; gets separate LLM-backed reply.
  - Switch back USER; USER sees only USER evidence clerk chat and cursor resumes from USER stream.
  - Shared evidence catalog shows only `PARTIES` evidence to both sides.
  - Private evidence is visible only to owner and platform roles if that policy is approved.

- [ ] **Step 6: Commit and push after implementation**

  Commit sequence should be small:

  1. `feat: add agent conversation session persistence`
  2. `feat: isolate intake agent memory by session`
  3. `feat: isolate evidence agent memory by session`
  4. `feat: add agent invocation context to python harness`
  5. `feat: add prompt profile selection for room agents`
6. `fix: harden frontend actor session switching`
  7. `feat: route case permissions through access sessions`
  8. `fix: scope room messages and events by actor audience`

---

## 3. Acceptance checklist

### Access-session permission acceptance

- [ ] Every current room/case/review/outcome API resolves a server-owned `case_access_session` or receives one from its caller.
- [ ] USER access session has `PARTY_USER` and cannot pass review read/decision permissions.
- [ ] MERCHANT access session has `PARTY_MERCHANT` and cannot pass review read/decision permissions.
- [ ] PLATFORM_REVIEWER access session has `REVIEWER_ALL` and can read all case rooms/artifacts plus perform review decisions.
- [ ] ADMIN and SYSTEM sessions pass privileged checks through `ADMIN_ALL` / `SYSTEM_ALL`, not raw role shortcuts.
- [ ] CUSTOMER_SERVICE can perform service/intake assistance but cannot perform final review approval unless its permission level is explicitly changed.
- [ ] Merchant cannot view the same order user’s intake-room private chat or memory frame.
- [ ] User cannot view the same order merchant’s intake-room private chat or memory frame.
- [ ] Privileged reviewer reads party-private material through access permission but never merges that material into party Agent memory.

### Isolation acceptance

- [ ] Same case + same room + same role + different `actor_id` produces different `agent_session_id`.
- [ ] Same case + same room + same actor + different `agent_key` produces different `agent_session_id`.
- [ ] Every `agent_conversation_session` links to one `case_access_session`.
- [ ] Agent invocation context includes `access_session_id`, `permission_level`, and `permission_scopes`.
- [ ] Intake recent turns are loaded by `agent_session_id`, not by `case_id`.
- [ ] Evidence recent turns are loaded by `agent_session_id`, not by `role`.
- [ ] Latest memory endpoint returns only current actor’s agent session memory for party roles.
- [ ] Python rejects or ignores recent turns whose `agent_session_id` does not match `agent_context.agent_session_id`.
- [ ] Frontend actor switch clears stale session data before new data arrives.
- [ ] SSE cursor is isolated by `case_id + room_type + actor_id + actor_role`.

### Prompt/profile acceptance

- [ ] Intake Officer receives `agent_context` in every call.
- [ ] Evidence Clerk receives `agent_context` in every call.
- [ ] Prompt repository can select role-specific prompt variants.
- [ ] Prompt profile id is persisted in room memory for audit.
- [ ] LLM prompt does not contain counterparty private chat history.

### Data-model acceptance

- [ ] `case_access_session` has unique scope for actor/case/permission level.
- [ ] `agent_conversation_session` has unique scope for actor/session.
- [ ] `agent_session_dossier` stores private right-side board drafts by session.
- [ ] `room_turn_memory` has `agent_session_id` on all new participant and agent writes.
- [ ] `room_turn_memory` has `access_session_id` on all new participant and agent writes.
- [ ] `case_intake_dossier` records only the accepted/frozen case-level handoff and its source actor/session.
- [ ] Party-private `room_message` and `case_event` records are actor-aware, not only role-aware.
- [ ] Existing rows are either safely backfilled or excluded from session-sensitive reads.
- [ ] No frontend API accepts client-supplied `agent_session_id`.

### Evidence-room product acceptance

- [ ] USER and MERCHANT can both participate in the same evidence room.
- [ ] Their chats with Evidence Clerk are private and isolated.
- [ ] Shared evidence catalog remains shared only for `PARTIES` evidence.
- [ ] Evidence Clerk only asks about evidence source, authenticity, completeness, relevance, and contradictions.
- [ ] Evidence Clerk does not output liability, refund, compensation, or final remedy.

### Regression acceptance

- [ ] Intake confirmation still opens evidence room.
- [ ] External import still creates visible dispute cases.
- [ ] Existing notifications and room events still replay after reconnect.
- [ ] Existing hearing route remains reachable after evidence completion.
- [ ] Focused Java/Python/frontend suites pass.
- [ ] Browser E2E path passes with real UI interactions.

---

## 4. Execution strategy after review

Recommended execution: subagent-driven development, but not with multiple implementers editing the same files in parallel.

Safe parallelism:

- One subagent implements Task 1 while another read-only reviewer prepares expected tests for Task 2.
- Review subagents can run in parallel after each implementation task.
- Frontend work starts only after Java/Python contract fields are stable.

Unsafe parallelism:

- Do not let Java intake and evidence implementers both edit `RoomTurnMemoryEntity`, `RoomTurnMemoryRepository`, or migration files at the same time.
- Do not let Python prompt/harness and workflow implementers both change `final_agents.py` simultaneously.

Controller rule:

- Implementation tasks execute sequentially by contract layer: DB → Java intake → Java evidence → Python harness → prompt profiles → frontend → E2E.
- Reviews and targeted audits may run in parallel.

---

## 5. Open confirmation point

Before coding, product confirmation needed for one policy:

**Private evidence catalog visibility**

Option A, recommended: `PRIVATE` evidence existence/id/status is hidden from the counterparty; only owner and platform roles can see it.

Option B: counterparty can see redacted metadata that a private item exists, but cannot read content.

This plan assumes Option A unless you choose otherwise.
