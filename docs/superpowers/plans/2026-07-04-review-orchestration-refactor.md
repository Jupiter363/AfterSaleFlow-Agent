# Review Orchestration Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构平台审核后的编排链路，让审核服务只负责持久化审核事实，由独立后置编排服务负责执行、结案和评估。

**Architecture:** 新增 `PostReviewOrchestrationService` 作为审核完成后的唯一后置编排入口。`ReviewApplicationService` 在事务内完成审核记录和状态更新，事务外调用后置编排；执行仍复用 `ToolExecutorService` 的冻结审核包治理校验，结案仍复用 `CaseClosureService`。

**Tech Stack:** Spring Boot, Spring Data JPA, Temporal Java SDK, PostgreSQL/Testcontainers, JUnit 5, Mockito, Vue/Vite browser E2E.

---

## File Structure

- Modify: `java-api-service/src/main/java/com/example/dispute/review/application/ReviewApplicationService.java`
  - Remove direct Temporal workflow signalling.
  - Inject and call `PostReviewOrchestrationService`.
- Create: `java-api-service/src/main/java/com/example/dispute/review/application/PostReviewOrchestrationService.java`
  - Own post-review execution, closure and non-executable decision routing.
- Create: `java-api-service/src/main/java/com/example/dispute/review/application/PostReviewOrchestrationResult.java`
  - Small immutable result for orchestration status.
- Modify: `java-api-service/src/test/java/com/example/dispute/review/ReviewApplicationServiceIntegrationTest.java`
  - Assert review service persists decision and delegates orchestration.
- Create: `java-api-service/src/test/java/com/example/dispute/review/PostReviewOrchestrationServiceIntegrationTest.java`
  - Verify approved decisions execute and close the case.
- Modify if needed: `java-api-service/src/main/java/com/example/dispute/review/application/ReviewDecisionView.java`
  - Keep API compatible unless tests show the frontend needs orchestration status.

## Task 1: Extract post-review orchestration boundary

**Files:**
- Create: `java-api-service/src/main/java/com/example/dispute/review/application/PostReviewOrchestrationResult.java`
- Create: `java-api-service/src/main/java/com/example/dispute/review/application/PostReviewOrchestrationService.java`
- Test: `java-api-service/src/test/java/com/example/dispute/review/PostReviewOrchestrationServiceIntegrationTest.java`

- [ ] **Step 1: Write the failing test for approved review orchestration**

Add a test named `approvedReviewExecutesApprovedActionsAndClosesCase`.

Expected behavior:

- Given a case in `APPROVED_FOR_EXECUTION`.
- Given a frozen approval record that points at the latest frozen packet and approved remedy plan.
- When `PostReviewOrchestrationService.orchestrate("APPROVAL_x", actor, "orchestrate-key")` runs.
- Then at least one action record is created with `SUCCEEDED`.
- Then the case becomes `CLOSED` if evaluation agent returns a valid completed report.

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
cd java-api-service
.\mvnw -Dtest=PostReviewOrchestrationServiceIntegrationTest#approvedReviewExecutesApprovedActionsAndClosesCase test
```

Expected: compilation failure because `PostReviewOrchestrationService` does not exist.

- [ ] **Step 3: Implement the orchestration service**

Create `PostReviewOrchestrationResult` as a record with:

```java
public record PostReviewOrchestrationResult(
        String approvalRecordId,
        String caseId,
        String status,
        boolean executionAttempted,
        boolean closureAttempted,
        String message) {}
```

Create `PostReviewOrchestrationService` with:

- Dependencies:
  - `ApprovalRecordRepository`
  - `FulfillmentCaseRepository`
  - `ToolExecutorService`
  - `CaseClosureService`
- Public method:
  - `PostReviewOrchestrationResult orchestrate(String approvalRecordId, AuthenticatedActor actor, String idempotencyKey)`
- Behavior:
  - Load approval record.
  - If decision is `APPROVE` or `MODIFY_AND_APPROVE`, call `ToolExecutorService.executeApprovedActions(caseId, "POST_REVIEW_EXECUTE:" + approvalRecordId + ":" + idempotencyKey, SYSTEM)`.
  - Then call `CaseClosureService.close(caseId, "POST_REVIEW_CLOSE:" + approvalRecordId + ":" + idempotencyKey, SYSTEM, "TRACE_POST_REVIEW_" + caseId, "REQ_POST_REVIEW_" + approvalRecordId)`.
  - If decision is `REQUEST_MORE_EVIDENCE`, return `WAITING_EVIDENCE` without execution.
  - If decision is `REJECT` or `ESCALATE_MANUAL`, return `MANUAL_HANDOFF` without execution.

- [ ] **Step 4: Run the test and verify GREEN**

Run:

```powershell
cd java-api-service
.\mvnw -Dtest=PostReviewOrchestrationServiceIntegrationTest#approvedReviewExecutesApprovedActionsAndClosesCase test
```

Expected: PASS.

## Task 2: Refactor ReviewApplicationService away from Temporal signalling

**Files:**
- Modify: `java-api-service/src/main/java/com/example/dispute/review/application/ReviewApplicationService.java`
- Modify: `java-api-service/src/test/java/com/example/dispute/review/ReviewApplicationServiceIntegrationTest.java`

- [ ] **Step 1: Write the failing review delegation test**

Add or modify a test named `decidePersistsReviewAndDelegatesPostReviewOrchestration`.

Expected behavior:

- Mock `PostReviewOrchestrationService`.
- Call `ReviewApplicationService.decide(...)`.
- Verify an approval record exists.
- Verify `PostReviewOrchestrationService.orchestrate(approvalRecordId, reviewerActor, idempotencyKey)` is called.
- Verify no `WorkflowClient.newWorkflowStub(...)` call is needed by this service.

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
cd java-api-service
.\mvnw -Dtest=ReviewApplicationServiceIntegrationTest#decidePersistsReviewAndDelegatesPostReviewOrchestration test
```

Expected: FAIL because `ReviewApplicationService` still calls Temporal workflow signalling directly.

- [ ] **Step 3: Refactor service dependencies and decision flow**

Change constructor:

- Remove `WorkflowClient workflowClient`.
- Add `PostReviewOrchestrationService postReviewOrchestration`.

Change `decide(...)`:

- Keep `transactions.execute(ignored -> persistDecision(...))`.
- Load the persisted approval record or use the returned id.
- Call `postReviewOrchestration.orchestrate(result.approvalRecordId(), actor, command.idempotencyKey())`.
- Return the existing `ReviewDecisionView`.

Remove:

- `signal(...)` private method.
- `FulfillmentDisputeWorkflow` import.
- `HumanReviewSignal` import.
- `WorkflowClient` field.

- [ ] **Step 4: Run the test and verify GREEN**

Run:

```powershell
cd java-api-service
.\mvnw -Dtest=ReviewApplicationServiceIntegrationTest test
```

Expected: PASS.

## Task 3: Preserve non-executable decision behavior

**Files:**
- Modify: `java-api-service/src/test/java/com/example/dispute/review/PostReviewOrchestrationServiceIntegrationTest.java`
- Modify if needed: `java-api-service/src/main/java/com/example/dispute/review/application/PostReviewOrchestrationService.java`

- [ ] **Step 1: Write tests for non-executable decisions**

Add tests:

- `requestMoreEvidenceDoesNotExecuteActions`
- `manualHandoffDoesNotExecuteActions`

Expected behavior:

- `REQUEST_MORE_EVIDENCE` returns orchestration status `WAITING_EVIDENCE`.
- `ESCALATE_MANUAL` returns orchestration status `MANUAL_HANDOFF`.
- No action records are created.
- Case status remains the status already written by `FulfillmentCaseEntity.applyReviewOutcome(...)`.

- [ ] **Step 2: Run tests and verify RED or GREEN**

Run:

```powershell
cd java-api-service
.\mvnw -Dtest=PostReviewOrchestrationServiceIntegrationTest test
```

Expected: PASS if Task 1 implementation already covers these branches; otherwise FAIL with missing branch behavior.

- [ ] **Step 3: Implement missing branches**

If failing, add explicit branch handling in `PostReviewOrchestrationService`.

- [ ] **Step 4: Run tests and verify GREEN**

Run:

```powershell
cd java-api-service
.\mvnw -Dtest=PostReviewOrchestrationServiceIntegrationTest test
```

Expected: PASS.

## Task 4: Keep governance checks intact

**Files:**
- Modify: `java-api-service/src/test/java/com/example/dispute/review/FrozenReviewPacketTest.java`
- Modify: `java-api-service/src/test/java/com/example/dispute/executor/ToolExecutorServiceIntegrationTest.java`
- Modify if needed: `java-api-service/src/main/java/com/example/dispute/review/application/PostReviewOrchestrationService.java`

- [ ] **Step 1: Run existing governance tests**

Run:

```powershell
cd java-api-service
.\mvnw -Dtest=FrozenReviewPacketTest,ToolExecutorServiceIntegrationTest test
```

Expected: PASS.

- [ ] **Step 2: Fix any dependency injection breakage only**

If tests fail because `ReviewApplicationService` constructor changed, update test configuration/mocks to provide `PostReviewOrchestrationService`.

- [ ] **Step 3: Re-run governance tests**

Run:

```powershell
cd java-api-service
.\mvnw -Dtest=FrozenReviewPacketTest,ToolExecutorServiceIntegrationTest test
```

Expected: PASS.

## Task 5: Browser E2E verification

**Files:**
- No production file change expected unless browser verification exposes a real bug.

- [ ] **Step 1: Confirm local hot-dev services**

Run:

```powershell
Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8000/health'
Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8081/actuator/health'
Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:5173/disputes'
```

Expected:

- Python agent health is UP.
- Java API health is UP.
- Vite disputes page returns 200.

- [ ] **Step 2: Complete UI flow in browser**

Use the in-app browser at:

```text
http://127.0.0.1:5173/disputes
```

Flow:

1. Create or open a dispute order.
2. Complete intake.
3. Submit user and merchant evidence.
4. Enter hearing.
5. Reach settlement or adjudication draft.
6. Open reviewer workbench.
7. Approve the frozen review packet.
8. Confirm no `review persisted but workflow signal failed` appears.
9. Open the outcome page and verify final decision/action records render.

- [ ] **Step 3: Verify database state**

Use PostgreSQL query against Docker PostgreSQL:

```sql
select fc.id, fc.case_status, ar.decision_type, count(act.id) as action_count
from fulfillment_dispute_case fc
left join approval_record ar on ar.case_id = fc.id
left join action_record act on act.case_id = fc.id
where fc.id = '<CASE_ID>'
group by fc.id, fc.case_status, ar.decision_type;
```

Expected:

- `case_status = CLOSED` after execution and closure.
- `decision_type = APPROVE` or `MODIFY_AND_APPROVE`.
- `action_count > 0`.

## Self-Review

- Spec coverage: plan covers service boundary extraction, review service refactor, non-executable decisions, governance checks, and browser E2E.
- Placeholder scan: no TBD/TODO placeholders remain.
- Type consistency: `PostReviewOrchestrationService`, `PostReviewOrchestrationResult`, `ReviewApplicationService`, and existing executor/closure services use consistent names.
